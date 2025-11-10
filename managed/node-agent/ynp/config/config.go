package config

import (
	"context"
	"fmt"
	"log"
	md "node-agent/app/task/module"
	"os"
	"path/filepath"
	"strings"
	"time"

	"gopkg.in/ini.v1"
)

type Args struct {
	Command        string
	YnpBasePath    string
	SpecificModule string
	ConfigFile     string
	PreflightCheck bool
	ExtraVars      map[string]any
	DryRun         bool
}

type Module interface {
	BasePath() string
	Name() string
	RenderTemplates(ctx context.Context, values map[string]any) (*RenderedTemplates, error)
}

type RenderedTemplates struct {
	Name      string
	Templates map[string]string // phase -> content
}

type BaseModule struct {
	basePath string
	name     string
}

func NewBaseModule(name, basePath string) *BaseModule {
	return &BaseModule{
		basePath: basePath,
		name:     name,
	}
}

func (bm *BaseModule) RenderTemplates(
	ctx context.Context,
	values map[string]any,
) (*RenderedTemplates, error) {
	templates := map[string]string{
		"run":      "run.j2",
		"precheck": "precheck.j2",
	}
	output := &RenderedTemplates{Name: bm.Name(), Templates: make(map[string]string)}
	for key, templateFile := range templates {
		templatePath := filepath.Join(bm.BasePath(), "templates", templateFile)
		if _, err := os.Stat(templatePath); os.IsNotExist(err) {
			continue
		}
		rendered, err := md.ResolveTemplate(ctx, values, templatePath)
		if err != nil {
			err = fmt.Errorf("failed to render template %s: %w", templatePath, err)
			return nil, err
		}
		output.Templates[key] = rendered
	}
	return output, nil
}

func (bm *BaseModule) BasePath() string {
	return bm.basePath
}

func (bm *BaseModule) Name() string {
	return bm.name
}

func (bm *BaseModule) String() string {
	return bm.name + "@" + bm.basePath
}

type CommandFactory func(map[string]map[string]any, Args) Command

// Command represents a command to be executed.
type Command interface {
	Validate() error
	DryRun() error
	RunPreflightChecks() error
	Execute(specificModule string) error
	Cleanup()
}

func processNestedConfigs(
	ynpConfig map[string]map[string]any,
) (map[string]map[string]any, error) {
	out := make(map[string]map[string]any)
	for sectionKey, sectionValues := range ynpConfig {
		if sectionKey == ini.DefaultSection {
			continue
		}
		if !strings.Contains(sectionKey, ".") {
			for defaultKey, defaultValue := range ynpConfig[ini.DefaultSection] {
				if _, exists := sectionValues[defaultKey]; !exists {
					sectionValues[defaultKey] = defaultValue
				}
			}
			for k, v := range sectionValues {
				if out[sectionKey] == nil {
					out[sectionKey] = make(map[string]any)
				}
				out[sectionKey][k] = v
			}
			continue
		}
		defaultKeys, ok := ynpConfig[ini.DefaultSection]
		if !ok {
			defaultKeys = map[string]any{}
		}
		for defaultKey := range defaultKeys {
			// Remove default keys from section.
			delete(sectionValues, defaultKey)
		}
		keyList := strings.Split(sectionKey, ".")
		sectionKey = keyList[0]
		nested, exists := out[sectionKey]
		if !exists {
			nested = make(map[string]any)
			out[sectionKey] = nested
		}
		// First key is section name, last key is where to put the values.
		for i := 1; i < len(keyList)-1; i++ {
			key := keyList[i]
			if _, exists := nested[key]; !exists {
				nested[key] = make(map[string]any)
			}
			nested = nested[key].(map[string]any)
		}
		nested[keyList[len(keyList)-1]] = sectionValues
	}
	return out, nil
}

func GenerateConfigINI(
	ctx context.Context,
	values map[string]any,
	args Args,
) (map[string]map[string]any, error) {
	configTemplate := filepath.Join(args.YnpBasePath, "configs/config.j2")
	configPath := filepath.Join(args.YnpBasePath, "configs/config.ini")
	ynpValues := map[string]any{
		"ynp_config": values,
		"ynp_dir":    args.YnpBasePath,
		"start_time": time.Now().Unix(),
	}
	renderedConfig, err := md.CopyFile(ctx, ynpValues, configTemplate, configPath, 0644, "")
	if err != nil {
		return nil, err
	}
	iniConfig, err := ini.Load([]byte(renderedConfig))
	if err != nil {
		log.Fatalf("Fail to read INI content: %v", err)
	}
	configOutput := map[string]map[string]any{}
	for _, section := range iniConfig.Sections() {
		sectionMap := make(map[string]any)
		for _, key := range section.Keys() {
			sectionMap[key.Name()] = key.Value()
		}
		configOutput[section.Name()] = sectionMap
	}
	configOutput = FixParsedConfig(configOutput).(map[string]map[string]any)
	return processNestedConfigs(configOutput)
}

func FixParsedConfig(input any) any {
	switch v := input.(type) {
	case map[any]any:
		var fixedMap = make(map[string]any)
		for key, val := range v {
			strKey := fmt.Sprintf("%v", key)
			fixedMap[strKey] = FixParsedConfig(val)
		}
		return fixedMap
	case map[string]any:
		var fixedMap = make(map[string]any)
		for k, val := range v {
			fixedMap[k] = FixParsedConfig(val)
		}
		return fixedMap
	case []any:
		var fixedSlice []any
		for _, item := range v {
			fixedSlice = append(fixedSlice, FixParsedConfig(item))
		}
		return fixedSlice
	case string:
		lower := strings.ToLower(v)
		if lower == "true" || lower == "false" {
			return lower == "true"
		}
		return v
	default:
		return input
	}
}

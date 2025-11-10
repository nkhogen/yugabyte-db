package main

import (
	"context"
	"encoding/json"
	"log"
	"node-agent/util"
	"node-agent/ynp"
	"node-agent/ynp/config"
	"os"
	"path/filepath"

	"github.com/spf13/cobra"
	"gopkg.in/yaml.v2"
)

var (
	rootCmd = &cobra.Command{
		Use:           "node-agent-provision ...",
		Short:         "Command for node agent provisioner",
		SilenceUsage:  true,
		SilenceErrors: true,
	}
)

func parseArguments() config.Args {
	command := rootCmd.Flags().String("command", "provision", "Command to execute")
	ynpBasePath := rootCmd.Flags().
		String("ynp_base_path", "./modules", "Path to the YNP base directory")
	specificModule := rootCmd.Flags().String("specific_module", "", "Specific module to execute")
	configFile := rootCmd.Flags().String(
		"config_file",
		"./node-agent-provision.yaml",
		"Path to the ynp configuration file",
	)
	preflightCheck := rootCmd.Flags().Bool(
		"preflight_check",
		false,
		"Execute the pre-flight check on the node",
	)
	extraVars := rootCmd.Flags().String(
		"extra_vars",
		"{}",
		"Path to the JSON file or JSON string containing extra variables required for execution.",
	)
	dryRun := rootCmd.Flags().Bool(
		"dry-run",
		false,
		"Render Execution Scripts without executing them for dry-run",
	)
	rootCmd.MarkFlagRequired("ynp_base_path")
	err := rootCmd.Execute()
	if err != nil {
		log.Fatalf("Failed to parse arguments: %v", err)
	}

	var eVars map[string]any
	if *extraVars != "" {
		eVars = loadJSONOrFile(*extraVars)
	}

	return config.Args{
		Command:        *command,
		YnpBasePath:    *ynpBasePath,
		SpecificModule: *specificModule,
		ConfigFile:     *configFile,
		PreflightCheck: *preflightCheck,
		ExtraVars:      eVars,
		DryRun:         *dryRun,
	}
}

func loadJSONOrFile(jsonOrPath string) map[string]any {
	// Check if the input is a file path and if it exists.
	if _, err := os.Stat(jsonOrPath); err == nil {
		data, err := os.ReadFile(jsonOrPath)
		if err != nil {
			log.Fatalf("Failed to read extra_vars file: %v", err)
		}
		var result map[string]any
		if err := json.Unmarshal(data, &result); err != nil {
			log.Fatalf("Failed to parse extra_vars JSON file: %v", err)
		}
		return result
	}
	// Try parsing as a JSON string.
	var result map[string]any
	if err := json.Unmarshal([]byte(jsonOrPath), &result); err != nil {
		log.Fatalf("Invalid JSON or file path for extra_vars: %v", err)
	}
	return result
}

func loadYAMLConfig(filePath string) map[string]any {
	absConfigPath, err := filepath.Abs(filePath)
	if err != nil {
		log.Fatalf("Failed to get absolute path: %v", err)
	}
	configData, err := os.ReadFile(absConfigPath)
	if err != nil {
		log.Fatalf("Parsing config file failed with: %v", err)
	}
	var ynpConfig map[string]any
	if err := yaml.Unmarshal(configData, &ynpConfig); err != nil {
		log.Fatalf("Failed to parse YAML config: %v", err)
	}
	// Fix the types in the parsed config.
	return config.FixParsedConfig(ynpConfig).(map[string]any)
}

func main() {
	ctx := context.Background()
	args := parseArguments()
	ynpConfig := loadYAMLConfig(args.ConfigFile)
	// Merge extra_vars into ynpConfig, giving preference to extra_vars.
	for key, value := range args.ExtraVars {
		if section, ok := ynpConfig[key].(map[string]any); ok {
			// Update existing section.
			if vmap, ok := value.(map[string]any); ok {
				for k, v := range vmap {
					section[k] = v
				}
			}
		} else {
			// Add new section.
			ynpConfig[key] = value
		}
	}
	config.SetupLogger(ynpConfig)
	iniConfig, err := config.GenerateConfigINI(ctx, ynpConfig, args)
	if err != nil {
		log.Fatalf("Failed to generate config.ini: %v", err)
	}
	jsonConfig, _ := json.MarshalIndent(iniConfig, "", "  ")
	util.ConsoleLogger().Infof(ctx, "Config here: %+v\n: %s", iniConfig, string(jsonConfig))
	executor := ynp.NewExecutor(iniConfig, args)
	err = executor.Exec()
	if err != nil {
		log.Fatalf("Failed to execute provision command: %v", err)
	}
}

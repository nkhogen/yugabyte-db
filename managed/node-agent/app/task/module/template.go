// Copyright (c) YugabyteDB, Inc.

package module

import (
	"context"
	"io/fs"
	"node-agent/util"
	"os"
	"path"
	"path/filepath"
	"strings"

	"github.com/nikolalohinski/gonja/v2"
	"github.com/nikolalohinski/gonja/v2/exec"
	"github.com/nikolalohinski/gonja/v2/loaders"
)

func CopyFile(
	ctx context.Context,
	values map[string]any,
	templateSubpath, destination string,
	mod fs.FileMode,
	username string,
) (string, error) {
	userDetail, err := util.UserInfo(username)
	if err != nil {
		return "", err
	}
	templatePath := templateSubpath
	if !strings.HasPrefix(templateSubpath, "/") {
		templatePath = filepath.Join(util.TemplateDir(), templateSubpath)
	}
	util.FileLogger().Infof(ctx, "Resolving template file %s", templatePath)
	output, err := ResolveTemplate(ctx, values, templatePath)
	if err != nil {
		util.FileLogger().Errorf(ctx, "Resolution failed for template file %s", templatePath)
		return "", err
	}
	output = strings.TrimSpace(output)
	file, err := os.OpenFile(destination, os.O_TRUNC|os.O_RDWR|os.O_CREATE, mod)
	if err != nil {
		util.FileLogger().Errorf(ctx, "Error in creating file %s - %s", destination, err.Error())
		return "", err
	}
	defer file.Close()
	if !userDetail.IsCurrent {
		err = file.Chown(int(userDetail.UserID), int(userDetail.GroupID))
		if err != nil {
			util.FileLogger().
				Errorf(ctx, "Error in changing file owner %s - %s", destination, err.Error())
			return "", err
		}
	}
	_, err = file.WriteString(output)
	if err != nil {
		return "", err
	}
	return output, nil
}

func splitServers(e *exec.Evaluator, in *exec.Value, params *exec.VarArgs) *exec.Value {
	if in.IsError() {
		return in
	}
	value := in.String()
	value = strings.Trim(value, "\"")
	tokens := strings.Split(value, ",")
	for i := range tokens {
		tokens[i] = strings.TrimSpace(tokens[i])
	}
	return exec.AsValue(tokens) // nothing to do here, just to keep track of the safe application
}

func ResolveTemplate(
	ctx context.Context,
	values map[string]any,
	templatePath string,
) (string, error) {
	loader, err := loaders.NewFileSystemLoader(path.Dir(templatePath))
	if err != nil {
		return "", err
	}
	gonja.DefaultEnvironment.Filters.Register("split_servers", splitServers)
	tpl, err := exec.NewTemplate(
		path.Base(templatePath),
		gonja.DefaultConfig,
		loader,
		gonja.DefaultEnvironment,
	)
	if err != nil {
		return "", err
	}
	return tpl.ExecuteToString(exec.NewContext(values))
}

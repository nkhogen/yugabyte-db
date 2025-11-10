package ynp

import (
	"fmt"
	"node-agent/ynp/command"
	"node-agent/ynp/config"
)

type Executor struct {
	Config   map[string]map[string]any
	Args     config.Args
	Commands map[string]config.CommandFactory
}

func NewExecutor(values map[string]map[string]any, args config.Args) *Executor {
	return &Executor{
		Config: values,
		Args:   args,
		Commands: map[string]config.CommandFactory{
			"provision": command.NewProvisionCommand,
		},
	}
}

func (e *Executor) Exec() error {
	factory, ok := e.Commands[e.Args.Command]
	if !ok {
		return fmt.Errorf("unsupported command: %s", e.Args.Command)
	}
	command := factory(e.Config, e.Args)
	// Need to validate only in case of onprem nodes.
	if len(e.Args.ExtraVars) == 0 {
		if err := command.Validate(); err != nil {
			return err
		}
	}
	if e.Args.DryRun {
		return command.DryRun()
	}
	if e.Args.PreflightCheck {
		return command.RunPreflightChecks()
	}
	return command.Execute(e.Args.SpecificModule)
}

// Stub for ProvisionCommand and its factory

package command

import (
	"context"
	"fmt"
	"log"
	"node-agent/ynp/config"
	backuputils "node-agent/ynp/module/provision/backup_utils"
	"node-agent/ynp/module/provision/chrony"
	"node-agent/ynp/module/provision/clockbound"
	"node-agent/ynp/module/provision/configureos"
	"node-agent/ynp/module/provision/configuresudoers"
	"node-agent/ynp/module/provision/configurethp"
	"node-agent/ynp/module/provision/installconfigureearlyoom"
	"node-agent/ynp/module/provision/installpackages"
	"node-agent/ynp/module/provision/mountephemeraldrives"
	"node-agent/ynp/module/provision/network"
	"node-agent/ynp/module/provision/nodeagent"
	"node-agent/ynp/module/provision/nodeexporter"
	"node-agent/ynp/module/provision/rebootnode"
	"node-agent/ynp/module/provision/sshd"
	"node-agent/ynp/module/provision/systemd"
	"node-agent/ynp/module/provision/updateos"
	"node-agent/ynp/module/provision/ybmami"
	"node-agent/ynp/module/provision/yugabyte"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

type OSFamily string

const (
	RedHat  OSFamily = "RedHat"
	Debian  OSFamily = "Debian"
	Suse    OSFamily = "Suse"
	Arch    OSFamily = "Arch"
	Unknown OSFamily = "Unknown"
)

type ProvisionCommand struct {
	ctx               context.Context
	Config            map[string]map[string]any
	CloudOnlyModules  map[string]struct{}
	OnPremOnlyModules map[string]struct{}
	Modules           map[string]config.Module
}

func NewProvisionCommand(values map[string]map[string]any, args config.Args) config.Command {
	command := &ProvisionCommand{
		Config: values,
		CloudOnlyModules: map[string]struct{}{
			"Preprovision": {}, "MountEpemeralDrive": {}, "InstallPackages": {},
		},
		OnPremOnlyModules: map[string]struct{}{"RebootNode": {}},
		Modules:           make(map[string]config.Module),
	}
	err := command.loadModule(args)
	if err != nil {
		log.Fatalf("Failed to load module: %v", err)
	}
	return command
}

func (pc *ProvisionCommand) loadModule(args config.Args) error {
	modulesPath := filepath.Join(args.YnpBasePath, "modules", "provision")
	pc.Modules[backuputils.ModuleName] = backuputils.NewBackupUtils(modulesPath)
	pc.Modules[chrony.ModuleName] = chrony.NewConfigureChrony(modulesPath)
	pc.Modules[clockbound.ModuleName] = clockbound.NewConfigureClockbound(modulesPath)
	pc.Modules[configureos.ModuleName] = configureos.NewConfigureOs(modulesPath)
	pc.Modules[configuresudoers.ModuleName] = configuresudoers.NewConfigureSudoers(modulesPath)
	pc.Modules[configurethp.ModuleName] = configurethp.NewConfigureTHP(modulesPath)
	pc.Modules[installconfigureearlyoom.ModuleName] = installconfigureearlyoom.NewInstallConfigureEarlyoom(
		modulesPath,
	)
	pc.Modules[installpackages.ModuleName] = installpackages.NewInstallPackages(modulesPath)
	pc.Modules[mountephemeraldrives.ModuleName] = mountephemeraldrives.NewMountEphemeralDrive(
		modulesPath,
	)
	pc.Modules[network.ModuleName] = network.NewConfigureNetwork(modulesPath)
	pc.Modules[nodeagent.ModuleName] = nodeagent.NewInstallNodeAgent(modulesPath)
	pc.Modules[nodeexporter.ModuleName] = nodeexporter.NewConfigureNodeExporter(modulesPath)
	pc.Modules[rebootnode.ModuleName] = rebootnode.NewRebootNode(modulesPath)
	pc.Modules[sshd.ModuleName] = sshd.NewConfigureSshD(modulesPath)
	pc.Modules[systemd.ModuleName] = systemd.NewConfigureSystemd(modulesPath)
	pc.Modules[updateos.ModuleName] = updateos.NewPreprovision(modulesPath)
	pc.Modules[ybmami.ModuleName] = ybmami.NewConfigureYBMAMI(modulesPath)
	pc.Modules[yugabyte.ModuleName] = yugabyte.NewCreateYugabyteUser(modulesPath)
	return nil
}

func (pc *ProvisionCommand) Validate() error {
	// TODO enable later
	//return pc.validateRequiredPackages()
	return nil
}
func (pc *ProvisionCommand) DryRun() error {
	installScript, precheckScript, err := pc.generateTemplate("")
	if err != nil {
		return err
	}
	log.Printf("Install Script: %s", installScript)
	log.Printf("Precheck Script: %s", precheckScript)
	return nil
}

func (pc *ProvisionCommand) RunPreflightChecks() error {
	_, precheckScript, err := pc.generateTemplate("")
	if err != nil {
		return err
	}
	pc.compareYnpVersion()
	pc.runScript(precheckScript)
	// TODO check RC.
	return nil
}

func (pc *ProvisionCommand) Execute(specificModule string) error {
	for _, ctx := range pc.Config {
		if ctx["is_ybm"] == true {
			pc.copyTemplatesFilesForYBM(ctx)
		}
	}
	runScript, precheckScript, err := pc.generateTemplate(specificModule)
	if err != nil {
		return err
	}
	provisionResult := pc.runScript(runScript)
	precheckResult := pc.runScript(precheckScript)
	pc.saveYnpVersion()
	if provisionResult != 0 || precheckResult != 0 {
		return fmt.Errorf(
			"provisioning failed with code %d, precheck failed with code %d",
			provisionResult,
			precheckResult,
		)
	}
	return nil
}

func (pc *ProvisionCommand) Cleanup() {}

func (pc *ProvisionCommand) validateRequiredPackages() error {
	pm := pc.getPackageManager()
	pkgs := []string{"openssl", "policycoreutils"}
	cloudPkgs := []string{"gzip"}
	for _, pkg := range pkgs {
		if err := pc.checkPackage(pm, pkg); err != nil {
			return err
		}
	}
	for _, ctx := range pc.Config {
		if isCloud, ok := ctx["is_cloud"].(bool); ok && isCloud {
			for _, pkg := range cloudPkgs {
				if err := pc.checkPackage(pm, pkg); err != nil {
					return err
				}
			}
		}
	}
	return nil
}

func (pc *ProvisionCommand) getPackageManager() string {
	if _, err := exec.LookPath("rpm"); err == nil {
		return "rpm"
	}
	if _, err := exec.LookPath("dpkg"); err == nil {
		return "deb"
	}
	log.Fatal("Unsupported package manager. Cannot determine package installation status.")
	return ""
}

func (pc *ProvisionCommand) checkPackage(pm, pkg string) error {
	var cmd *exec.Cmd
	if pm == "rpm" {
		cmd = exec.Command("rpm", "-q", pkg)
	} else {
		cmd = exec.Command("dpkg", "-s", pkg)
	}
	err := cmd.Run()
	if err != nil {
		log.Printf("%s is not installed.", pkg)
		return err
	} else {
		log.Printf("%s is installed.", pkg)
	}
	return nil
}

func (pc *ProvisionCommand) runScript(scriptPath string) int {
	cmd := exec.Command("/bin/bash", "-lc", scriptPath)
	out, err := cmd.CombinedOutput()
	//config.Logger().Infof(pc.ctx, "Output: %s", string(out))
	log.Printf("Output: %s", string(out))
	if err != nil {
		log.Printf("Error: %v", err)
	}
	return cmd.ProcessState.ExitCode()
}

func (pc *ProvisionCommand) generateTemplate(specificModule string) (string, string, error) {
	dist, fam, ver := pc.getOSInfo()
	allTemplates := make([]*config.RenderedTemplates, 0)
	getBool := func(m map[string]any, key string) bool {
		if val, ok := m[key].(bool); ok {
			return val
		}
		return false
	}
	for key, values := range pc.Config {
		module, ok := pc.Modules[key]
		if !ok {
			log.Printf("Module not found: %s", key)
			continue
		}
		if specificModule != "" && key != specificModule {
			continue
		}
		if _, ok := pc.CloudOnlyModules[key]; ok && !getBool(values, "is_cloud") {
			continue
		}
		if _, ok := pc.OnPremOnlyModules[key]; ok && getBool(values, "is_cloud") {
			continue
		}
		if key == "InstallNodeAgent" && !getBool(values, "is_install_node_agent") {
			fmt.Printf(
				"Skipping %s because is_install_node_agent is %v\n",
				key,
				values["is_install_node_agent"],
			)
			continue
		}
		if key == "ConfigureClockbound" && !getBool(values, "configure_clockbound") {
			fmt.Printf("Skipping %s because %s.configure_clockbound is %v\n",
				key, key, values["configure_clockbound"])
			continue
		}
		if key == "ConfigureSudoers" && !getBool(values, "sudoers_commands") {
			fmt.Printf("Skipping %s because %s.sudoers_commands is not set\n",
				key, key)
			continue
		}
		values["templatedir"] = filepath.Join(filepath.Dir(module.BasePath()), "templates")
		values["os_family"] = fam
		values["os_version"] = ver
		values["os_distribution"] = dist
		log.Printf("Rendering templates for module %s", key)
		rendered, err := module.RenderTemplates(pc.ctx, values)
		if err != nil {
			log.Printf("Error rendering templates for module %s: %v", key, err)
			return "", "", err
		}
		if rendered != nil {
			allTemplates = append(allTemplates, rendered)
		}
		//fmt.Printf("Rendered templates for module: %s -> %s\n", key, rendered)
	}
	runScript, err := pc.buildScript(allTemplates, "run")
	if err != nil {
		return "", "", err
	}
	precheckScript, err := pc.buildScript(allTemplates, "precheck")
	if err != nil {
		return "", "", err
	}
	return runScript, precheckScript, nil
}

func (pc *ProvisionCommand) addResultHelper(f *os.File) {
	fmt.Fprintf(f, `
            # Initialize the JSON results array
            json_results='{\n"results":[\n'

            add_result() {
                local check="$1"
                local result="$2"
                local message="$3"
                if [ "${#json_results}" -gt 20 ]; then
                    json_results+=',\n'
                fi
                json_results+='    {\n'
                json_results+='      "check": "'$check'",\n'
                json_results+='      "result": "'$result'",\n'
                json_results+='      "message": "'$message'"\n'
                json_results+='    }'
            }
	`)
}

func (pc *ProvisionCommand) printResultHelper(f *os.File) {
	fmt.Fprintf(f, `
            print_results() {
                any_fail=0
                if [[ $json_results == *'"result": "FAIL"'* ]]; then
                    any_fail=1
                fi
                json_results+='\n]}'

                # Output the JSON
                echo "$json_results"

                # Exit with status code 1 if any check has failed
                if [ $any_fail -eq 1 ]; then
                    echo "Pre-flight checks failed, Please fix them before continuing."
                    exit 1
                else
                    echo "Pre-flight checks successful"
                fi
            }

            print_results
			`)
}

func (pc *ProvisionCommand) populateSudoCheck(f *os.File) {
	fmt.Fprintf(f, "\n######## Check the SUDO Access #########\n")
	fmt.Fprintf(f, "SUDO_ACCESS=\"false\"\n")
	fmt.Fprintf(f, "if [ $(id -u) = 0 ]; then\n")
	fmt.Fprintf(f, "  SUDO_ACCESS=\"true\"\n")
	fmt.Fprintf(f, "elif sudo -n pwd >/dev/null 2>&1; then\n")
	fmt.Fprintf(f, "  SUDO_ACCESS=\"true\"\n")
	fmt.Fprintf(f, "fi\n")
}

func (pc *ProvisionCommand) buildScript(
	allTemplates []*config.RenderedTemplates,
	phase string,
) (string, error) {
	key := ""
	for k := range pc.Config {
		key = k
		break
	}
	ctx := pc.Config[key]
	dir := "/tmp"
	if tmp, ok := ctx["tmp_directory"].(string); ok {
		dir = tmp
	}
	f, err := os.CreateTemp(dir, "*.sh")
	if err != nil {
		return "", err
	}
	defer f.Close()
	f.WriteString("#!/bin/bash\n\n")
	if ctx["loglevel"] == "DEBUG" {
		f.WriteString("set -x\n")
	}
	pc.addResultHelper(f)
	pc.populateSudoCheck(f)
	// Add helpers (stubbed)
	for _, tmpl := range allTemplates {
		if rendered, ok := tmpl.Templates[phase]; ok {
			fmt.Fprintf(f, "\n######## BEGIN %s #########\n", key)
			fmt.Fprint(f, rendered)
			fmt.Fprintf(f, "\n######## END %s #########\n", key)
		}
	}
	pc.printResultHelper(f)
	os.Chmod(f.Name(), 0755)
	log.Printf("Temp file for %s is: %s", phase, f.Name())
	return f.Name(), nil
}

func (pc *ProvisionCommand) getOSInfo() (string, OSFamily, string) {
	osRelease := "/etc/os-release"
	data, err := os.ReadFile(osRelease)
	if err != nil {
		return "", Unknown, ""
	}
	lines := strings.Split(string(data), "\n")
	info := make(map[string]string)
	for _, line := range lines {
		if strings.Contains(line, "=") {
			parts := strings.SplitN(line, "=", 2)
			info[parts[0]] = strings.Trim(parts[1], `"`)
		}
	}
	dist := strings.ToLower(info["ID"])
	ver := info["VERSION_ID"]
	major := ""
	if ver != "" {
		major = strings.Split(ver, ".")[0]
	}
	var fam OSFamily
	switch dist {
	case "rhel", "centos", "almalinux", "ol", "fedora":
		fam = RedHat
	case "ubuntu", "debian":
		fam = Debian
	case "suse", "opensuse", "sles":
		fam = Suse
	case "arch":
		fam = Arch
	default:
		fam = Unknown
	}
	return dist, fam, major
}

func (pc *ProvisionCommand) copyTemplatesFilesForYBM(ctx map[string]interface{}) {
	ynpDir, _ := ctx["ynp_dir"].(string)
	modulesPath := filepath.Join(ynpDir, "modules/provision")
	systemdDir := filepath.Join(modulesPath, "systemd/templates/")
	ybmDir := filepath.Join(modulesPath, "ybm_ami/templates/")
	files := []string{
		"clean_cores.sh.j2",
		"zip_purge_yb_logs.sh.j2",
		"collect_metrics_wrapper.sh.j2",
	}
	for _, f := range files {
		src := filepath.Join(systemdDir, f)
		dest := filepath.Join(ybmDir, f)
		data, err := os.ReadFile(src)
		if err == nil {
			os.WriteFile(dest, data, 0644)
		}
	}
}

func (pc *ProvisionCommand) saveYnpVersion() {
	// Stub: implement file write and chown logic as needed
}

func (pc *ProvisionCommand) compareYnpVersion() {
	// Stub: implement version comparison logic as needed
}

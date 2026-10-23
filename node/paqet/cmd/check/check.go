package check

import (
	"fmt"
	"github.com/spf13/cobra"
	"paqet/internal/conf"
)

var path string
var Cmd = &cobra.Command{Use: "check", Short: "Validate configuration without opening packet sockets", RunE: func(cmd *cobra.Command, args []string) error {
	_, err := conf.LoadFromFile(path)
	if err != nil {
		return err
	}
	fmt.Fprintln(cmd.OutOrStdout(), "configuration valid")
	return nil
}}

func init() { Cmd.Flags().StringVarP(&path, "config", "c", "config.yaml", "Configuration path") }

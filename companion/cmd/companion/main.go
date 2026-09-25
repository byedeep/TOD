// The step-one companion is a CLI scaffold. It does not ingest observations yet.
package main

import (
	"encoding/json"
	"fmt"
	"os"
)

const version = "0.1.0-diagnostic"

func main() {
	if len(os.Args) != 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "version":
		fmt.Println("Bed Wars Companion " + version)
	case "doctor":
		_ = json.NewEncoder(os.Stdout).Encode(map[string]any{
			"version": version, "stage": "build-and-capture", "ingestion_available": false,
			"discord_available": false, "message": "Diagnostic captures are written by the Forge mod; no credentials required.",
		})
	default:
		usage()
		os.Exit(2)
	}
}

func usage() { fmt.Fprintln(os.Stderr, "Usage: companion version|doctor") }

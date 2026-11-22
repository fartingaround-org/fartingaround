package ui

import (
	"reflect"
	"testing"
)

func TestBackupsArgs(t *testing.T) {
	got, err := backupsArgs("setup", " s3:https://example.test/bucket ", "", "", "", 0, 0, 0, map[string]string{"AWS_SECRET_ACCESS_KEY": "s3cr3t value", "AWS_ACCESS_KEY_ID": "id", "EMPTY": ""})
	want := []string{"backups", "setup", "--repo", "s3:https://example.test/bucket", "--env", "AWS_ACCESS_KEY_ID=id", "--env", "AWS_SECRET_ACCESS_KEY=s3cr3t value"}
	if err != nil || !reflect.DeepEqual(got, want) {
		t.Fatalf("setup = %v, %v\nwant    %v", got, err, want)
	}
	// no address: the place another site on the server already uses
	got, err = backupsArgs("setup", "  ", "", "", "", 0, 0, 0, nil)
	if err != nil || !reflect.DeepEqual(got, []string{"backups", "setup"}) {
		t.Fatalf("setup joining the existing place = %v, %v", got, err)
	}
	got, err = backupsArgs("keep", "", "", "", "", 7, 4, 6, nil)
	if err != nil || !reflect.DeepEqual(got, []string{"backups", "set", "keep", "daily=7", "weekly=4", "monthly=6"}) {
		t.Fatalf("keep = %v, %v", got, err)
	}
	got, err = backupsArgs("env", "", "", "B2_ACCOUNT_KEY", "", 0, 0, 0, nil)
	if err != nil || !reflect.DeepEqual(got, []string{"backups", "set", "env", "B2_ACCOUNT_KEY="}) {
		t.Fatalf("removing a key = %v, %v", got, err)
	}
	for name, bad := range map[string]func() ([]string, error){
		"unknown action":      func() ([]string, error) { return backupsArgs("restore", "", "", "", "", 0, 0, 0, nil) },
		"a key that is not":   func() ([]string, error) { return backupsArgs("env", "", "", "lower-case", "v", 0, 0, 0, nil) },
		"a newline in a value": func() ([]string, error) { return backupsArgs("env", "", "", "KEY", "a\nb", 0, 0, 0, nil) },
		"a silly keep count":  func() ([]string, error) { return backupsArgs("keep", "", "", "", "", -1, 0, 0, nil) },
		"a bad key in setup":  func() ([]string, error) { return backupsArgs("setup", "/mnt/b", "", "", "", 0, 0, 0, map[string]string{"bad key": "v"}) },
	} {
		if _, err := bad(); err == nil {
			t.Errorf("%s should be refused", name)
		}
	}
}

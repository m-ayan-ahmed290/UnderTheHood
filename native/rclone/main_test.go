package main

import (
	"encoding/json"
	"github.com/rclone/rclone/lib/oauthutil"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func rpc(t *testing.T, m string, p any) map[string]any {
	t.Helper()
	b, _ := json.Marshal(p)
	r := map[string]any{}
	out := call(m, string(b))
	if e := json.Unmarshal([]byte(out), &r); e != nil {
		t.Fatal(e, out)
	}
	if e, ok := r["error"]; ok && e != nil && e != "" {
		t.Fatalf("%s: %v", m, e)
	}
	return r
}
func TestEngine(t *testing.T) {
	dir := t.TempDir()
	rpc(t, "uth/init", map[string]string{"path": filepath.Join(dir, "rclone.conf"), "password": "test-config-key-never-plaintext"})
	t.Run("providers", func(t *testing.T) {
		p := rpc(t, "config/providers", map[string]any{})["providers"].([]any)
		names := map[string]bool{}
		for _, v := range p {
			m := v.(map[string]any)
			names[m["Prefix"].(string)] = true
		}
		for _, n := range []string{"drive", "onedrive", "dropbox", "mega", "local", "crypt", "alias"} {
			if !names[n] {
				t.Fatal("missing", n)
			}
		}
	})
	t.Run("OAuth defaults and overrides", func(t *testing.T) {
		for _, custom := range []bool{false, true} {
			name := "default"
			params := map[string]string{}
			if custom {
				name = "custom"
				params["client_id"] = "my-app-id"
				params["client_secret"] = "my-test-secret"
			}
			rpc(t, "config/create", map[string]any{"name": name, "type": "drive", "parameters": params, "opt": map[string]bool{"nonInteractive": true, "all": true}})
			c := rpc(t, "config/get", map[string]string{"name": name})
			if custom && c["client_id"] != "my-app-id" {
				t.Fatal("lost override")
			}
			if !custom && c["client_id"] != nil && c["client_id"] != "" {
				t.Fatal("unexpected override")
			}
		}
	})

	t.Run("reconnect keeps custom OAuth", func(t *testing.T) {
		rpc(t, "config/update", map[string]any{"name": "custom", "parameters": map[string]string{}, "opt": map[string]bool{"nonInteractive": true, "all": true}})
		c := rpc(t, "config/get", map[string]string{"name": "custom"})
		if c["client_id"] != "my-app-id" {
			t.Fatal("reconnect lost custom application")
		}
		rpc(t, "fscache/clear", map[string]any{})
	})
	t.Run("browser handoff", func(t *testing.T) {
		oauthutil.OpenURL("http://127.0.0.1:53682/auth?state=test")
		if !strings.Contains(rpc(t, "uth/authURL", map[string]any{})["url"].(string), "state=test") {
			t.Fatal("handoff failed")
		}
		if rpc(t, "uth/authURL", map[string]any{})["url"] != "" {
			t.Fatal("not consumed")
		}
	})
	t.Run("local copy list move delete", func(t *testing.T) {
		src := filepath.Join(dir, "src")
		dst := filepath.Join(dir, "dst")
		os.MkdirAll(src, 0700)
		os.MkdirAll(dst, 0700)
		content := []byte("Unicode 文件 café\nreal bytes")
		os.WriteFile(filepath.Join(src, "source.txt"), content, 0600)
		p := map[string]string{"srcFs": src, "srcRemote": "source.txt", "dstFs": dst, "dstRemote": "copy.txt"}
		rpc(t, "operations/copyfile", p)
		b, e := os.ReadFile(filepath.Join(dst, "copy.txt"))
		if e != nil || string(b) != string(content) {
			t.Fatal("bytes differ", e)
		}
		if len(rpc(t, "operations/list", map[string]string{"fs": dst, "remote": ""})["list"].([]any)) != 1 {
			t.Fatal("list mismatch")
		}
		rpc(t, "operations/movefile", map[string]string{"srcFs": dst, "srcRemote": "copy.txt", "dstFs": dst, "dstRemote": "renamed.txt"})
		rpc(t, "operations/deletefile", map[string]string{"fs": dst, "remote": "renamed.txt"})
		if _, e = os.Stat(filepath.Join(dst, "renamed.txt")); !os.IsNotExist(e) {
			t.Fatal("delete failed")
		}
	})

	t.Run("asynchronous alias transfer and progress", func(t *testing.T) {
		dst := filepath.Join(dir, "async")
		os.MkdirAll(dst, 0700)
		rpc(t, "config/create", map[string]any{"name": "async-alias", "type": "alias", "parameters": map[string]string{"remote": dst}, "opt": map[string]bool{"nonInteractive": true}})
		result := rpc(t, "operations/copyfile", map[string]any{"srcFs": filepath.Join(dir, "src"), "srcRemote": "source.txt", "dstFs": "async-alias:", "dstRemote": "UnderTheHood/objects/test/0", "_async": true, "_group": "async-fixture"})
		deadline := time.Now().Add(3 * time.Second)
		for {
			status := rpc(t, "job/status", map[string]any{"jobid": result["jobid"]})
			rpc(t, "core/stats", map[string]string{"group": "async-fixture"})
			if status["finished"] == true {
				if status["success"] != true {
					t.Fatal(status["error"])
				}
				break
			}
			if time.Now().After(deadline) {
				t.Fatal("job timed out")
			}
			time.Sleep(20 * time.Millisecond)
		}
		rpc(t, "core/stats-reset", map[string]string{"group": "async-fixture"})
		if _, err := os.Stat(filepath.Join(dst, "UnderTheHood/objects/test/0")); err != nil {
			t.Fatal(err)
		}
	})
	t.Run("encrypted config", func(t *testing.T) {
		b, e := os.ReadFile(filepath.Join(dir, "rclone.conf"))
		if e != nil {
			t.Fatal(e)
		}
		if !strings.Contains(string(b), "RCLONE_ENCRYPT_V0") || strings.Contains(string(b), "my-test-secret") {
			t.Fatal("unencrypted config")
		}
	})
	t.Run("argon2id", func(t *testing.T) {
		p := map[string]string{"password": "long test passphrase", "salt": "AAAAAAAAAAAAAAAAAAAAAA=="}
		a := rpc(t, "uth/derive", p)["key"]
		if a != rpc(t, "uth/derive", p)["key"] {
			t.Fatal("not deterministic")
		}
		p["password"] = "different passphrase"
		if a == rpc(t, "uth/derive", p)["key"] {
			t.Fatal("password ignored")
		}
	})
	t.Run("no request secrets in errors", func(t *testing.T) {
		s := call("unknown/method", `{"client_secret":"never-expose-this"}`)
		if strings.Contains(s, "never-expose-this") || !strings.Contains(s, "error") {
			t.Fatal("bad error", s)
		}
	})
}

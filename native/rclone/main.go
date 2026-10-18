package main

/*
#include <stdlib.h>
*/
import "C"
import (
	"encoding/base64"
	"encoding/json"
	_ "github.com/rclone/rclone/backend/all"
	"github.com/rclone/rclone/fs"
	"github.com/rclone/rclone/fs/config"
	_ "github.com/rclone/rclone/fs/operations"
	_ "github.com/rclone/rclone/fs/sync"
	"github.com/rclone/rclone/lib/oauthutil"
	"github.com/rclone/rclone/librclone/librclone"
	"golang.org/x/crypto/argon2"
	"os"
	"sync"
	"unsafe"
)

var once sync.Once
var authMu sync.Mutex
var authURL string

func call(method, input string) string {
	var p map[string]string
	switch method {
	case "uth/derive":
		if err := json.Unmarshal([]byte(input), &p); err != nil {
			return `{"error":"invalid request"}`
		}
		salt, err := base64.StdEncoding.DecodeString(p["salt"])
		if err != nil || len(salt) != 16 {
			return `{"error":"invalid salt"}`
		}
		key := argon2.IDKey([]byte(p["password"]), salt, 3, 64*1024, 2, 32)
		b, _ := json.Marshal(map[string]string{"key": base64.StdEncoding.EncodeToString(key)})
		return string(b)
	case "uth/init":
		if err := json.Unmarshal([]byte(input), &p); err != nil {
			return `{"error":"invalid request"}`
		}
		var initErr error
		once.Do(func() {
			fs.GetConfig(nil).LogLevel = fs.LogLevelEmergency
			fs.GetConfig(nil).AskPassword = false
			initErr = config.SetConfigPath(p["path"])
			if initErr != nil {
				return
			}
			initErr = config.SetConfigPassword(p["password"])
			if initErr != nil {
				return
			}
			os.Setenv("RCLONE_CONFIG_PASS", p["password"])
			librclone.Initialize()
			oauthutil.OpenURL = func(url string) error { authMu.Lock(); authURL = url; authMu.Unlock(); return nil }
		})
		if initErr != nil {
			return `{"error":"engine initialization failed"}`
		}
		return `{}`
	case "uth/authURL":
		authMu.Lock()
		b, _ := json.Marshal(map[string]string{"url": authURL})
		authURL = ""
		authMu.Unlock()
		return string(b)
	}
	out, status := librclone.RPC(method, input)
	if status >= 400 {
		var v map[string]interface{}
		json.Unmarshal([]byte(out), &v)
		b, _ := json.Marshal(map[string]interface{}{"error": v["error"], "status": status})
		return string(b)
	}
	return out
}

//export UthCall
func UthCall(method *C.char, input *C.char) *C.char {
	return C.CString(call(C.GoString(method), C.GoString(input)))
}

//export UthFree
func UthFree(p *C.char) { C.free(unsafe.Pointer(p)) }
func main()             {}

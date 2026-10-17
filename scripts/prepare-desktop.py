"""Package host-native Rclone and web assets into the Tauri resources."""
import json, os, pathlib, shutil, subprocess
root=pathlib.Path(__file__).resolve().parent.parent
resources=root/'apps/desktop/src-tauri/resources'
resources.mkdir(parents=True,exist_ok=True)
source=json.loads(subprocess.check_output(['go','mod','download','-json','github.com/rclone/rclone@v1.75.2']))['Dir']
name='rclone.exe' if os.name=='nt' else 'rclone'
subprocess.run(['go','build','-trimpath','-buildvcs=false','-ldflags=-s -w','-o',str(resources/name),'.'],cwd=source,check=True)
shutil.copytree(root/'apps/web/dist',resources/'web',dirs_exist_ok=True)
shutil.copyfile(pathlib.Path(source)/'COPYING',resources/'RCLONE-LICENSE.txt')
p=root/'apps/desktop/src-tauri/tauri.conf.json'
config=json.loads(p.read_text());config['bundle']['resources']={f'resources/{name}':name,'resources/web/':'web/','resources/RCLONE-LICENSE.txt':'RCLONE-LICENSE.txt'}
p.write_text(json.dumps(config,indent=2)+'\n')

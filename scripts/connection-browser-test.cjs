const {chromium}=require('playwright');
const assert=require('node:assert/strict');const fs=require('node:fs');const path=require('node:path');const os=require('node:os');const {spawn}=require('node:child_process');
(async()=>{
 const root=path.resolve(__dirname,'..');const data=fs.mkdtempSync(path.join(os.tmpdir(),'hood-connect-'));
 const binary=process.env.UTH_RCLONE||path.join(root,'target/rclone-test');
 const companion=spawn(path.join(root,'target/debug/hood-companion'),[],{env:{...process.env,UTH_DATA:data,UTH_RCLONE:binary,UTH_WEB_ASSETS:path.join(root,'apps/web/dist'),UTH_NO_BROWSER:'1'},stdio:['ignore','pipe','pipe']});
 let browser;
 try{
  const link=await new Promise((resolve,reject)=>{let text='';const timer=setTimeout(()=>reject(Error('Companion did not start')),10000);companion.stdout.on('data',b=>{text+=b;const match=text.match(/http:\/\/127\.0\.0\.1:47651\/#[^ ]+/);if(match){clearTimeout(timer);resolve(match[0])}});companion.on('exit',()=>{clearTimeout(timer);reject(Error('Companion exited'))})});
  browser=await chromium.launch({headless:true,...(process.env.UTH_CHROMIUM?{executablePath:process.env.UTH_CHROMIUM}:{}),args:['--no-sandbox']});
  const page=await browser.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto(link);await page.locator('.sidebar-bottom').filter({hasText:'Companion connected'}).waitFor();
  await page.locator('nav').getByRole('button',{name:/Accounts/}).click();await page.locator('.page-title button.primary').click();
  const modal=page.getByRole('dialog');
  for(const [name,provider] of [['M. Ayan Drive','drive'],['A.A OneDrive','onedrive'],['M. Ayan Drive','drive']]){
   await modal.getByLabel('Account name',{exact:true}).fill(name);await modal.getByLabel('Cloud service').selectOption(provider);
   await modal.getByRole('button',{name:'Connect account',exact:true}).click();
   await modal.getByRole('button',{name:/Open sign-in page/}).waitFor({timeout:20000});
   assert(await modal.getByLabel('Account name',{exact:true}).isDisabled());
   await modal.getByRole('button',{name:'Cancel connection',exact:true}).click();
   await modal.getByText('Connection cancelled. You can retry this account name.',{exact:true}).waitFor();
  }
  await modal.getByLabel('Account name',{exact:true}).fill('invalid:name');await modal.getByRole('button',{name:'Connect account',exact:true}).click();
  assert((await modal.getByRole('alert').innerText()).includes('Use 1–64'));
  await modal.getByLabel('Account name',{exact:true}).fill('  A.A Local.v2  ');await modal.getByLabel('Cloud service').selectOption('local');await modal.getByRole('button',{name:'Connect account',exact:true}).click();
  await modal.getByText('Account connected and checked',{exact:true}).waitFor();await modal.getByRole('button',{name:'Done',exact:true}).click();
  await page.getByRole('heading',{name:'A.A Local.v2',exact:true}).waitFor();assert.deepEqual(errors,[]);
  console.log('PASS: real browser form accepts reported names; Drive/OneDrive start OAuth; progress remains visible; cancel/retry and inline errors work; completed account appears');
 }finally{if(browser)await browser.close();companion.kill();await new Promise(resolve=>companion.once('exit',resolve));fs.rmSync(data,{recursive:true,force:true})}
})().catch(e=>{console.error(e);process.exit(1)});

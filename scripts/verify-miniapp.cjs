/* Local UI checks use fixture APIs; no requests reach a real Telegram bot or database.
 * Usage: node scripts/verify-miniapp.cjs [absolute path to a Playwright module]
 */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require(process.argv[2] || 'playwright');
const staticRoot = path.resolve(__dirname, '../src/main/resources/miniapp');
const output = path.resolve(__dirname, '../target/ui-preview');

async function main() {
  await fs.mkdir(output, {recursive:true});
  const files = new Map(await Promise.all((await fs.readdir(staticRoot)).map(async name => [name, await fs.readFile(path.join(staticRoot,name))])));
  const server = http.createServer((req,res) => {
    const url = new URL(req.url,'http://localhost');
    const name = url.pathname === '/app' ? 'miniapp.html' : url.pathname.startsWith('/static/') ? url.pathname.slice(8) : '';
    const body = files.get(name);
    if (!body) { res.writeHead(404); res.end(); return; }
    const type = {'.html':'text/html; charset=utf-8','.css':'text/css; charset=utf-8','.js':'application/javascript; charset=utf-8','.webp':'image/webp','.svg':'image/svg+xml'};
    res.writeHead(200, {'Content-Type':type[path.extname(name)] || 'application/octet-stream'});
    res.end(body);
  });
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  const base = 'http://127.0.0.1:' + server.address().port;
  const browser = await chromium.launch({headless:true, ...(process.env.CHROME_PATH ? {executablePath:process.env.CHROME_PATH} : {})});
  const errors = [];
  const calls = [];
  let subscriptionFailure = false;
  let teamStatus = 'member';
  const context = await browser.newContext({viewport:{width:390,height:844},timezoneId:'Asia/Qyzylorda'});
  await context.addInitScript(() => {
    window.Telegram = {WebApp:{initData:'local-test-fixture',colorScheme:'light',ready(){},expand(){},onEvent(){}}};
    localStorage.setItem('reminder-theme',new URLSearchParams(location.search).get('theme') || 'light');
  });
  await context.route('https://telegram.org/**',route => route.fulfill({body:'',contentType:'application/javascript'}));
  await context.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const body = request.postDataJSON();
    calls.push({path:url.pathname,query:url.search,body});
    const today = new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Qyzylorda',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
    const tasks = [{taskId:'flowers',taskTitle:'Полить цветы',status:'Сегодня, 10:00',mine:true,done:false},{taskId:'report',taskTitle:'Подготовить отчёт',status:'Сегодня, 12:00',mine:true,done:false},{taskId:'walk',taskTitle:'Прогулка с Максом',status:'Сегодня, 18:00',mine:true,done:false},{taskId:'book',taskTitle:'Почитать книгу',status:'Сегодня, 21:00',mine:true,done:true}];
    const settings = {firstName:'Алексей',username:'alex',zoneId:'Asia/Qyzylorda',repingMinutes:5,alertsEnabled:true};
    const team = {status:teamStatus,teamName:'Моя семья',role:'OWNER',isOwner:true,memberCount:4,canManage:true,pendingCount:0};
    let data;
    switch (url.pathname) {
      case '/api/settings': data=settings; break;
      case '/api/team': data=team; break;
      case '/api/today': data={sections:[{user:'Алексей',tasks}]}; break;
      case '/api/team/members': data={viewerRole:'OWNER',members:[{chatId:1,name:'Алексей',role:'OWNER'},{chatId:2,name:'Анна',role:'MEMBER'},{chatId:3,name:'Максим',role:'MEMBER'},{chatId:4,name:'Соня',role:'MEMBER'}]}; break;
      case '/api/profile': data={streak:12,completedMonth:48,activeReminders:6,total:48,series:Array.from({length:14},(_,i)=>({date:today,count:[2,4,3,6,4,2,3,0,2,4,3,6,4,2][i]})),items:[{title:'Полить цветы',count:20},{title:'Подготовить отчёт',count:14},{title:'Прогулка с Максом',count:8},{title:'Почитать книгу',count:6}]}; break;
      case '/api/calendar/eager': data={days:{[today]:tasks.map(t=>({id:t.taskId,title:t.taskTitle,user:'Алексей',time:'10:00'}))}}; break;
      case '/api/tasks': case '/api/team/tasks': data={tasks:tasks.map((t,i)=>({id:t.taskId,number:i+1,title:t.taskTitle,frequency:'Каждый день'})),page:0,totalPages:1,total:4}; break;
      case '/api/task': data={id:'created',number:1,title:'Новое дело',kind:'MANUAL',kindHuman:'Ручное',frequency:'Без расписания',subscribers:0,isManual:true}; break;
      case '/api/task/new': case '/api/team/tasks/new': data={ok:true,id:'created',title:body.title}; break;
      case '/api/subscribe':
        if (subscriptionFailure) {subscriptionFailure=false; data={error:'Проверь время'};}
        else data={ok:true,taskTitle:'Новое дело'};
        break;
      case '/api/today/complete': await new Promise(resolve=>setTimeout(resolve,50)); data={ok:true}; break;
      case '/api/subs': data=[]; break;
      default: throw new Error('Unmocked API: '+url.pathname);
    }
    await route.fulfill({status:data.error?400:200,contentType:'application/json',body:JSON.stringify(data)});
  });
  const page = await context.newPage();
  page.on('pageerror',error=>errors.push(error.message));
  page.on('response',response=>{if(response.url().includes('/static/') && !response.ok()) errors.push(response.status()+' '+response.url());});
  const ready = async screen => {
    const selector = {today:'.home-task',calendar:'.calendar-grid',team:'.member-list',settings:'.profile-counters',newTask:'#new-task-form',success:'.success-screen',preferences:'.cat-theme-picker'}[screen];
    await page.locator(selector).first().waitFor();
    await page.evaluate(()=>Promise.all(Array.from(document.images).map(img=>img.complete?Promise.resolve():new Promise(resolve=>{img.onload=img.onerror=resolve;}))));
  };
  try {
    for (const theme of ['light','dark']) {
      for (const screen of ['today','calendar','team','settings','newTask','success','preferences']) {
        await page.goto(base+'/app?page='+screen+'&theme='+theme);
        await ready(screen);
        for (const width of [320,390,768]) {
          await page.setViewportSize({width,height:844});
          await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
          assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,theme+' '+screen+' overflows at '+width);
          if (!['newTask','success'].includes(screen)) {
            const navBounds = await page.locator('#nav').boundingBox();
            assert.ok(navBounds.y+navBounds.height <= 845, 'navigation must stay in the viewport');
          }
          if (width===390 && screen!=='preferences') await page.screenshot({path:path.join(output,screen+'-'+theme+'.png')});
        }
        assert.equal(await page.locator('#nav').isVisible(),!['newTask','success'].includes(screen));
      }
    }
    await page.setViewportSize({width:390,height:844});
    await page.goto(base+'/app?theme=light');
    await ready('today');
    const handler = await page.locator('.week-day').first().getAttribute('onclick');
    const selectedDate = handler.match(/date:'([^']+)'/)[1];
    await page.locator('.week-day').first().click();
    await ready('calendar');
    assert.equal(await page.locator('.calendar-day.selected').getAttribute('aria-label'),selectedDate);

    await page.locator('[data-page="team"]').click();
    await ready('team');
    await page.locator('[data-page="newTask"]').click();
    await ready('newTask');
    await page.waitForFunction(()=>document.querySelector('#new-scope option[value="team"]').textContent==='Моя семья');
    assert.equal(await page.locator('#new-scope').inputValue(),'team');
    await page.locator('#new-title').fill('Тестовая задача');
    await page.locator('#new-date').fill('2099-12-24');
    subscriptionFailure=true;
    await page.locator('#task-save').click();
    await page.locator('#task-save-error').waitFor();
    assert.equal(calls.filter(c=>c.path==='/api/team/tasks/new').length,1);
    await page.locator('#task-save').click();
    await ready('today');
    assert.equal(calls.filter(c=>c.path==='/api/team/tasks/new').length,1,'retry must not create another task');
    assert.equal(calls.filter(c=>c.path==='/api/subscribe').at(-1).body.taskRef,'created');
    await page.evaluate(()=>{window.__completeToday('flowers');window.__completeToday('flowers');});
    await ready('success');
    assert.equal(calls.filter(c=>c.path==='/api/today/complete').length,1);

    await page.goto(base+'/app?page=newTask&theme=light');
    await ready('newTask');
    await page.locator('#new-title').fill('Ручная задача');
    await page.locator('#new-repeat').selectOption('MANUAL');
    assert.equal(await page.locator('#new-time').isDisabled(),true);
    const subscribedBefore = calls.filter(c=>c.path==='/api/subscribe').length;
    await page.locator('#task-save').click();
    await page.locator('.page-header').filter({hasText:'Новое дело'}).waitFor();
    assert.equal(calls.filter(c=>c.path==='/api/subscribe').length,subscribedBefore);
    teamStatus='none';
    await page.goto(base+'/app?page=team&theme=light');
    await page.locator('#team-create-name').waitFor();
    assert.equal(errors.length,0,errors.join('\n'));
    console.log('PASS: both themes, 7 screens at 3 widths; selected date, team scope, save retry, manual task, completion and empty team. Screenshots: '+output);
  } finally {
    await context.close(); await browser.close(); await new Promise(resolve=>server.close(resolve));
  }
}
main().catch(error=>{console.error(error);process.exitCode=1;});

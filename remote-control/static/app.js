'use strict';
const $=s=>document.querySelector(s);
let dirty=false;
const fragment=new URLSearchParams(location.hash.slice(1));
let pair=fragment.get('pair')||sessionStorage.getItem('bydmate-pair'), registering=!!fragment.get('invite');
if(pair)sessionStorage.setItem('bydmate-pair',pair);
if(fragment.get('invite'))$('#login').elements.invite.value=fragment.get('invite');
history.replaceState(null,'','/');
function notice(text){$('#notice').textContent=text;}
function canonical(value){return value&&typeof value==='object'?Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])])):value;}
async function api(path,method='GET',data){let r;try{r=await fetch(path,{method,headers:data?{'Content-Type':'application/json'}:{},body:data?JSON.stringify(data):undefined});}catch(e){throw new Error('Нет связи с сервером. Попробуйте ещё раз.');}if(!r.headers.get('Content-Type')?.includes('application/json'))throw new Error('Сервер временно недоступен. Попробуйте ещё раз.');const value=await r.json();if(!r.ok)throw Object.assign(new Error(value.error||'Ошибка запроса'),{status:r.status});return value;}
function authMode(){ $('#invite-label').hidden=!registering;$('#auth-submit').textContent=registering?'Создать аккаунт':'Войти';$('#auth-switch').textContent=registering?'Уже есть аккаунт — войти':'Регистрация по приглашению';$('#login').elements.password.autocomplete=registering?'new-password':'current-password'; }
authMode();
$('#auth-switch').onclick=()=>{registering=!registering;authMode();};
$('#login').onsubmit=async e=>{e.preventDefault();const fields=Object.fromEntries(new FormData(e.target));try{if(registering)await api('/api/register','POST',fields);await api('/api/login','POST',fields);e.target.elements.password.value='';notice('');await refresh();}catch(err){notice(err.message);}};
$('#logout').onclick=async()=>{try{await api('/api/logout','POST',{});await refresh();}catch(e){notice(e.message);}};
$('#cars').addEventListener('input',()=>{dirty=true;});
$('#refresh').onclick=()=>{if(!dirty||confirm('Обновить состояние и сбросить несохранённые изменения формы?')){dirty=false;notice('');refresh();}};
$('#claim').onclick=async()=>{try{await api('/api/claim','POST',{token:pair});pair=null;sessionStorage.removeItem('bydmate-pair');notice('Автомобиль привязан');await refresh();}catch(e){notice(e.message);}};
function el(tag,text,cls){const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(cls)node.className=cls;return node;}
function check(label,value){const node=el('label',undefined,'check'),input=el('input');input.type='checkbox';input.checked=value;node.append(input,document.createTextNode(label));return {node,input};}
function number(label,value,min,max){const node=el('label',label),input=el('input');input.type='number';input.min=min;input.max=max;input.value=value;node.append(input);return {node,input};}
function climateForm(target){const box=el('div'),enabled=check('Климат включён',target.enabled),fields=el('div',undefined,'fields'),controls={enabled:enabled.input};box.append(enabled.node,fields);for(const [key,label,min,max]of [['temperature','Температура, °C',16,33],['fan','Обдув, уровень',1,7],['driver_heat','Подогрев водителя, 0–3',0,3],['passenger_heat','Подогрев пассажира, 0–3',0,3]]){const item=number(label,target[key],min,max);fields.append(item.node);controls[key]=item.input;}return {box,read:()=>Object.fromEntries(Object.entries(controls).map(([k,input])=>[k,input.type==='checkbox'?input.checked:Number(input.value)]))};}
function date(stamp){return stamp?new Date(stamp*1000).toLocaleString('ru-RU'):'нет данных';}
const statuses={pending:'Ожидает машину',success:'Команды приняты',partial:'Выполнено частично',failed:'Не выполнено',uncertain:'Результат неизвестен — повторите вручную',expired:'Истёк срок задания',superseded:'Заменено новым заданием',cancelled:'Отменено'};
function renderCar(car){const card=el('article',undefined,'car'),head=el('div',undefined,'car-head'),online=Date.now()/1000-car.seen<60;head.append(el('h2',car.name),el('span',online?'На связи':'Не в сети','badge'+(online?' online':'')));card.append(head,el('p','Последняя связь: '+date(car.seen)));const grid=el('div',undefined,'grid'),left=el('div'),right=el('div');grid.append(left,right);card.append(grid);
 const t=car.telemetry;if(t.latitude!==undefined&&t.longitude!==undefined&&t.location_time){const map=el('iframe');map.title='Положение автомобиля на Яндекс Картах';map.loading='lazy';map.referrerPolicy='no-referrer';map.src=`https://yandex.ru/map-widget/v1/?pt=${t.longitude},${t.latitude}&z=16&l=map`;left.append(map);const link=el('a','Открыть в Яндекс Картах');link.href=`https://yandex.ru/maps/?pt=${t.longitude},${t.latitude}&z=16&l=map`;link.target='_blank';link.rel='noopener noreferrer';left.append(el('p','Координаты на '+date(t.location_time)),link);}else left.append(el('p','GPS-координаты ещё не получены. В машине нужен доступ к местоположению.'));
 left.append(el('p',`Заряд: ${t.soc??'—'}% · За бортом: ${t.outside??'—'} °C · ADB: ${t.adb_ready?'готов':'недоступен'}`));
 const nameLabel=el('label','Название автомобиля'),carName=el('input');carName.value=car.name;carName.maxLength=80;nameLabel.append(carName);right.append(nameLabel);
 const startup=check('Применять при запуске BYDMate',car.config.startup),base=climateForm(car.config.climate);right.append(startup.node,el('h3','Основные настройки'),base.box);const rules={};for(const [key,title]of [['winter','Зима'],['summer','Лето']]){const rule=car.config[key],block=el('details',undefined,'rules'),summary=el('summary',title+' · '+(rule.enabled?'включено':'выключено')),enabled=check('Использовать правило',rule.enabled),threshold=number(key==='winter'?'При температуре не выше, °C':'При температуре не ниже, °C',rule.threshold,-50,60),cf=climateForm(rule.climate);block.append(summary,enabled.node,threshold.node,cf.box);right.append(block);rules[key]={enabled,threshold,cf};}
 const actions=el('div',undefined,'actions'),save=el('button','Сохранить'),apply=el('button','Применить сейчас','secondary'),unlink=el('button','Отвязать','danger'),saved=el('p','Изменения формы нужно сначала сохранить. Задание «сейчас» действует 15 минут.','saved');actions.append(save,apply,unlink);card.append(actions,saved);
 const read=()=>{const config={startup:startup.input.checked,climate:base.read()};for(const key of ['winter','summer']){const r=rules[key];config[key]={enabled:r.enabled.input.checked,threshold:Number(r.threshold.input.value),climate:r.cf.read()};}return config;};
 save.onclick=async()=>{save.disabled=true;try{await api(`/api/cars/${car.id}/config`,'PUT',{revision:car.revision,name:carName.value,config:read()});dirty=false;notice('Настройки сохранены');await refresh();}catch(e){notice(e.message);}finally{save.disabled=false;}};
 apply.onclick=async()=>{if(JSON.stringify(canonical(read()))!==JSON.stringify(canonical(car.config))){notice('Сначала сохраните изменения формы');return;}apply.disabled=true;try{await api(`/api/cars/${car.id}/apply`,'POST',{});notice('Задание отправлено. Результат появится здесь и в настроенном Telegram.');await refresh();}catch(e){notice(e.message);}finally{apply.disabled=false;}};
 unlink.onclick=async()=>{if(!confirm('Отвязать автомобиль и отключить удалённые правила?'))return;try{await api(`/api/cars/${car.id}/unlink`,'POST',{});await refresh();}catch(e){notice(e.message);}};
 if(car.last_command){const cmd=car.last_command;card.append(el('div',`Последнее задание · ${date(cmd.created)}\n${statuses[cmd.status]||cmd.status}${cmd.result?'\n'+cmd.result:''}`,'result'));}return card;
}
async function refresh(){try{const data=await api('/api/cars');$('#auth').hidden=true;$('#dashboard').hidden=false;$('#logout').hidden=false;$('#empty').hidden=!!data.cars.length;$('#pair-box').hidden=!pair;$('#cars').replaceChildren(...data.cars.map(renderCar));}catch(e){if(e.status===401){$('#auth').hidden=false;$('#dashboard').hidden=true;$('#logout').hidden=true;}else notice(e.message);}}
refresh();
// Do not replace forms while a user is editing them. Refresh on returning to the tab.
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&!$('#dashboard').hidden&&!dirty)refresh();});
setInterval(()=>{if(!document.hidden&&!$('#dashboard').hidden&&!dirty&&!$('#cars').contains(document.activeElement))refresh();},15000);

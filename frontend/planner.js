import { Timeline } from 'vis-timeline/standalone';
import { DataSet } from 'vis-data';
import moment from 'moment-timezone';
import 'moment/locale/sv';
import 'vis-timeline/styles/vis-timeline-graph2d.css';

const $ = id => document.getElementById(id);
const zone = 'Europe/Stockholm';
const defaultDayStartHour = 6;
const defaultDayEndHour = 18;
const date = value => value === undefined ? moment().tz(zone) : moment(value).tz(zone);
const local = value => date(value).format('YYYY-MM-DDTHH:mm');
const hours = minutes => `${Number((minutes / 60).toFixed(2))} tim`;
const statuses = { PLANNED:'Planerad', IN_PROGRESS:'Pågår', BLOCKED:'Hindrad', DONE:'Klar', CANCELLED:'Avbokad' };
let start = date().startOf('day'), days = 1, data, timeline, editing, busy = false, lastDrag = 0, timelineWindowKey = '';
let selectedProjects = new Set(), projectsInitialized = false;
const touchPlanning = window.matchMedia('(pointer: coarse)');
let canManage=false,isAdmin=document.body.dataset.isAdmin==='true',currentEmployeeId=0;
const items = new DataSet(), groups = new DataSet();
function element(tag, text, className) { const el=document.createElement(tag); if(text!=null) el.textContent=text; if(className) el.className=className; return el; }
function employeeTint(color,opacity=.18) { const match=/^#([0-9a-f]{6})$/i.exec(color||''); if(!match)return `rgba(100,116,139,${opacity})`;const value=parseInt(match[1],16);return `rgba(${value>>16},${(value>>8)&255},${value&255},${opacity})`; }
function message(text, error=false) { $('planner-message').textContent=text; $('planner-message').hidden=!text; $('planner-message').classList.toggle('error',error); }
function formError(id,error) { $(id).textContent=error?.message || ''; $(id).hidden=!error; }
async function api(path='',method='GET',body) {
 const response=await fetch(`/api/planning${path}`,{method,credentials:'same-origin',headers:{'Accept':'application/json',...(body?{'Content-Type':'application/json'}:{}),[$('csrf-header')?.content || document.querySelector('meta[name="csrf-header"]').content]:document.querySelector('meta[name="csrf-token"]').content},body:body?JSON.stringify(body):undefined});
 if(response.redirected || response.status===401) { location.assign('/login'); throw Error('Logga in igen.'); }
 if(!response.ok) { let detail; try {detail=await response.json();} catch {detail={message:response.status===403?'Du saknar behörighet.':'Ändringen kunde inte sparas.'};} throw Object.assign(Error(detail.message),detail); }
 if(response.status===204 || response.headers.get('content-length')==='0') return;
 const text=await response.text(); return text?JSON.parse(text):undefined;
}
function confirmAction(title,text,details=[],accept='Fortsätt') {
 $('confirm-title').textContent=title; $('confirm-message').textContent=text;
 $('confirm-details').replaceChildren(...details.map(x=>element('li',x))); $('confirm-accept').textContent=accept;
 const dialog=$('confirm-dialog'); dialog.showModal();
 return new Promise(resolve=> {let done=false; const finish=value=>{if(done)return;done=true;dialog.close();dialog.removeEventListener('cancel',cancel);resolve(value);};const cancel=()=>finish(false);dialog.addEventListener('cancel',cancel);$('confirm-cancel').onclick=()=>finish(false);$('confirm-accept').onclick=()=>finish(true);});
}
async function submitWithConflicts(path,method,body){try{return await api(path,method,body);}catch(error){if(error.code!=='STAFF_CONFLICT')throw error;const accepted=await confirmAction('Planerad tid överlappar eller överstiger kapaciteten','Den här aktiviteten krockar med en annan bokning eller går över arbetsdagens timmar. Vill du spara ändå?',error.conflicts.map(conflict=>`${conflict.name} · ${date(conflict.start).format('D MMM')}`),'Spara ändå');if(!accepted)throw Error('Ändringen avbröts.');return api(path,method,Array.isArray(body)?body.map(item=>({...item,allowConflict:true})):({...body,allowConflict:true}));}}
async function persist(booking){const body={name:booking.name,description:booking.description||'',calendarId:booking.calendarId,start:booking.start,end:booking.end,status:booking.status,employeeIds:booking.employeeIds,dailyAllocations:booking.dailyAllocations,sharedBlock:booking.sharedBlock,version:booking.version,allowConflict:false};const path=booking.id?`/bookings/${booking.id}`:'/bookings';return submitWithConflicts(path,booking.id?'PUT':'POST',body);}
async function persistBatch(bookings){return submitWithConflicts('/bookings/batch','POST',bookings.map(booking=>({...booking,allowConflict:false})));}async function load() {
 const response=await api(`?start=${encodeURIComponent(start.toISOString())}&end=${encodeURIComponent(start.clone().add(days,'days').toISOString())}`);
 data=response.data;
 isAdmin=data.admin;currentEmployeeId=data.employeeId;canManage=isAdmin||data.projects.some(project=>project.accessLevel==='LEAD');
 $('add-booking').hidden=!data.projects.some(project=>project.accessLevel==='LEAD');
 if(!projectsInitialized){selectedProjects=new Set(data.projects.map(p=>p.id));projectsInitialized=true;}
 render();
}
async function safely(task) {try {await task();}catch(error){message(error.message,true);}}
function render() {
 $('period-label').textContent=`${start.format('D MMM')} – ${start.clone().add(days-1,'days').format('D MMM YYYY')}`;
 const current=$('employee-filter').value;
 $('employee-filter').replaceChildren(new Option('Alla kollegor',''),...data.employees.map(e=>new Option(e.name,e.id)));
 $('employee-filter').value=current;
 $('project-list').replaceChildren(...data.projects.map(project=>{
  const label=element('label',null,'project-option'),input=element('input');input.type='checkbox';input.checked=selectedProjects.has(project.id);
  const dot=element('span',null,'project-dot');dot.style.backgroundColor=project.color;
  input.onchange=()=>{if(input.checked)selectedProjects.add(project.id);else selectedProjects.delete(project.id);renderViews();};
  label.append(input,dot,element('span',project.name));return label;
 }));
 renderViews();
}
function visiblePeople(){ const chosen=Number($('employee-filter').value); return data.employees.filter(e=>!chosen || chosen===e.id); }
function visibleBooking(b){const person=Number($('employee-filter').value);return selectedProjects.has(b.calendarId)&&(!person||b.employeeIds.includes(person));}
function projectAccess(id){return data.projects.find(project=>project.id===id);}
function canCreateInProject(id){return isAdmin||projectAccess(id)?.accessLevel==='LEAD';}
function canEditBooking(booking){const permission=projectAccess(booking.calendarId);return isAdmin||permission?.accessLevel==='LEAD'||permission?.accessLevel==='WORKER'&&!booking.sharedBlock&&currentEmployeeId>0&&booking.employeeIds.includes(currentEmployeeId);}
function canMoveBooking(booking){const permission=projectAccess(booking.calendarId);return isAdmin||permission?.accessLevel==='LEAD'||permission?.accessLevel==='WORKER'&&permission.canMoveOwn&&!booking.sharedBlock&&booking.employeeIds.length===1&&booking.employeeIds[0]===currentEmployeeId;}
function canChangeStatus(booking){return canEditBooking(booking);}
function hiddenTimes(){
 const settings=data.settings,startMinutes=Number(settings.workStart.slice(0,2))*60+Number(settings.workStart.slice(3,5));
 const longest=Math.max(settings.dailyMinutes,...data.employees.map(e=>e.dailyMinutes||settings.dailyMinutes));
 let first=Math.min(360,startMinutes),last=Math.max(1080,startMinutes+longest+settings.breakMinutes);
 for(const b of data.bookings){const a=date(b.start),z=date(b.end);if(a.format('YYYY-MM-DD')!==z.format('YYYY-MM-DD'))return [];first=Math.min(first,a.hours()*60+a.minutes());last=Math.max(last,z.hours()*60+z.minutes());}
 if(first<=0||last>=1440)return [];
 const clock=n=>`${String(Math.floor(n/60)).padStart(2,'0')}:${String(n%60).padStart(2,'0')}`;
 return [{start:`2026-01-01T${clock(last)}:00+01:00`,end:`2026-01-02T${clock(first)}:00+01:00`,repeat:'daily'}];
}
function renderViews() {
 const people=visiblePeople(),capacity=data.capacity.filter(c=>people.some(e=>e.id===c.employeeId));
 const periodStart=start.clone().startOf('day');
 const windowStart=days===1?periodStart.clone().hour(defaultDayStartHour).minute(0):periodStart;
 const windowEnd=days===1?periodStart.clone().hour(defaultDayEndHour).minute(0):periodStart.clone().add(days,'days');
 const visibleStart=windowStart.toDate(),visibleEnd=windowEnd.toDate();
 const nextWindowKey=`${visibleStart.toISOString()}/${visibleEnd.toISOString()}`;
 const total=key=>capacity.reduce((sum,c)=>sum+c[key],0);
 $('capacity-summary').replaceChildren(...[['Kapacitet',hours(total('capacityMinutes'))],['Planerad tid',hours(total('scheduledMinutes'))],['Ledig tid framåt',hours(total('availableMinutes'))],['Avslutat arbete',hours(total('completedMinutes'))]].map(([label,value])=>{const card=element('div',null,'metric');card.append(element('span',label,'metric-label'),element('strong',value,'metric-value'));return card;}));
 const rows=people.map(e=>{const c=data.capacity.find(c=>c.employeeId===e.id);const label=element('div',null,'staff-label');const avatar=element('span',e.name.slice(0,1),'staff-avatar');avatar.style.backgroundColor=employeeTint(e.color);avatar.style.color=e.color;const text=element('div');text.append(element('div',e.name,'staff-name'),element('div',`${hours(c.availableMinutes)} ledigt · ${c.busyNow?'Upptagen nu':'Tillgänglig nu'}`,'staff-load'));label.append(avatar,text);return{id:e.id,content:label};});
 rows.push({id:0,content:element('div','Obemannat','staff-name')});groups.clear();groups.add(rows);
 const entries=[];
 for(const c of capacity)for(const [n,slot] of c.freeSlots.entries())entries.push({id:`free-${c.employeeId}-${n}`,group:c.employeeId,start:slot.start,end:slot.end,type:'background',className:'free-slot',editable:false});
 for(const booking of data.bookings){
  for(const employeeId of booking.employeeIds.length?booking.employeeIds:[0]){
   if(employeeId&&!people.some(person=>person.id===employeeId))continue;
   const personRows=booking.dailyAllocations.filter(row=>row.employeeId===employeeId&&row.minutes>0);
   if(employeeId===0){const content=element('span',booking.name);content.title=`${booking.project}\n${date(booking.startDate).format('D MMM')}–${date(booking.endDate).format('D MMM YYYY')}`;entries.push({id:`booking-${booking.id}-0`,bookingId:booking.id,employeeId:0,group:0,start:booking.start,end:booking.end,content,type:'range',className:`booking-item status-${booking.status}`,style:`--employee-color:#64748B;--employee-tint:${employeeTint('#64748B')};--project-color:${booking.color}`,editable:false});continue;}
   const employee=data.employees.find(person=>person.id===employeeId),employeeColor=employee?.color||'#64748B';
   for(const row of personRows){const dayStart=moment.tz(`${row.date}T${row.startTime}`,zone),dayEnd=moment.tz(`${row.date}T${row.endTime}`,zone);const content=element('span',booking.name);content.title=`${booking.project}\n${date(row.date).format('D MMM')} ${row.startTime}–${row.endTime} · ${hours(row.minutes)}`;entries.push({id:`booking-${booking.id}-${employeeId}-${row.date}`,bookingId:booking.id,employeeId,group:employeeId,start:dayStart.toDate(),end:dayEnd.toDate(),workDate:row.date,content,type:'range',className:`booking-item status-${booking.status} ${selectedProjects.has(booking.calendarId)?'':'out-of-focus'}`,style:`--employee-color:${employeeColor};--employee-tint:${employeeTint(employeeColor)};--project-color:${booking.color}`,editable:touchPlanning.matches?false:{updateTime:canMoveBooking(booking),updateGroup:false,remove:false}});}
  }
 }
 items.clear();items.add(entries);
 if(!timeline) {
  timeline=new Timeline($('timeline'),items,groups,{locale:'sv',moment:value=>moment(value).tz(zone),stack:true,orientation:'top',start:visibleStart,end:visibleEnd,height:rows.length*76+90,minHeight:390,groupHeightMode:'auto',autoResize:true,margin:{item:8,axis:12},zoomMin:3*3600000,zoomMax:42*86400000,showCurrentTime:true,hiddenDates:hiddenTimes(),selectable:true,itemsAlwaysDraggable:false,multiselect:false,editable:!touchPlanning.matches,snap:value=>new Date(Math.round(+value/900000)*900000),onMoving:(item,callback)=>{lastDrag=Date.now();callback(item);},onMove:moveBooking});
  timelineWindowKey=nextWindowKey;
  timeline.on('doubleClick',event=>{if(busy||Date.now()-lastDrag<500)return;if(event.item){const item=items.get(event.item);if(item.bookingId)openBooking(data.bookings.find(b=>b.id===item.bookingId));else if(canManage)openBooking(null,event.time,event.group);}else if(canManage&&event.time)openBooking(null,event.time,event.group);});
  timeline.on('click',event=>{if(busy||Date.now()-lastDrag<500||!event.item)return;const item=items.get(event.item);if(item?.bookingId)openBooking(data.bookings.find(b=>b.id===item.bookingId));});
 } else {
  timeline.setOptions({height:rows.length*76+90,hiddenDates:hiddenTimes(),editable:!touchPlanning.matches});
  if(timelineWindowKey!==nextWindowKey){timeline.setWindow(visibleStart,visibleEnd,{animation:false});timelineWindowKey=nextWindowKey;}
 }
 renderBoard();renderReports();
}
async function moveBooking(item,callback) {
 if(busy){callback(null);return;}lastDrag=Date.now();busy=true;$('save-state').textContent='Sparar...';const original=data.bookings.find(booking=>booking.id===item.bookingId);if(!original){callback(null);busy=false;return;}
 const source=original.dailyAllocations.find(row=>row.employeeId===item.employeeId&&row.date===item.workDate);if(!source){callback(null);busy=false;return;}
 const targetDate=date(item.start),oldDate=date(source.date),dayShift=targetDate.diff(oldDate,'days'),oldMinute=Number(source.startTime.slice(0,2))*60+Number(source.startTime.slice(3,5)),newMinute=targetDate.hours()*60+targetDate.minutes(),minuteShift=newMinute-oldMinute;
 const shiftTime=(value)=>{const [h,m]=value.split(':').map(Number),total=h*60+m+minuteShift;if(total<0||total>=1440)throw Error('Aktiviteten måste rymmas inom samma dag.');return`${String(Math.floor(total/60)).padStart(2,'0')}:${String(total%60).padStart(2,'0')}`;};
 try {let dailyAllocations,first,last;if(dayShift===0&&minuteShift===0&&item.end){const newEnd=date(item.end),oldEnd=Number(source.endTime.slice(0,2))*60+Number(source.endTime.slice(3,5)),delta=newEnd.hours()*60+newEnd.minutes()-oldEnd;if(newEnd.format('YYYY-MM-DD')!==source.date||newEnd.hours()*60+newEnd.minutes()<=newMinute)throw Error('Sluttiden måste vara senare samma dag.');dailyAllocations=original.dailyAllocations.map(row=>{if(row.date!==source.date||(!original.sharedBlock&&row.employeeId!==source.employeeId))return row;const [h,m]=row.endTime.split(':').map(Number),total=h*60+m+delta;if(total<=0||total>=1440)throw Error('Aktiviteten måste rymmas inom samma dag.');const endTime=`${String(Math.floor(total/60)).padStart(2,'0')}:${String(total%60).padStart(2,'0')}`;return{...row,endTime,minutes:durationFor(row.startTime,endTime,date(row.date))};});first=date(original.startDate);last=date(original.endDate);}else{dailyAllocations=original.dailyAllocations.map(row=>{const workDate=date(row.date).add(dayShift,'days'),startTime=shiftTime(row.startTime),endTime=shiftTime(row.endTime);return{...row,date:workDate.format('YYYY-MM-DD'),startTime,endTime,minutes:row.minutes?durationFor(startTime,endTime,workDate):0};});first=date(original.startDate).add(dayShift,'days');last=date(original.endDate).add(dayShift,'days');}await persist({...original,start:first.clone().startOf('day').toISOString(),end:last.clone().add(1,'day').startOf('day').toISOString(),dailyAllocations});callback(item);await load();message('Aktiviteten har flyttats.');}catch(error){callback(null);await load().catch(()=>{});message(error.message,true);}finally{lastDrag=Date.now();busy=false;$('save-state').textContent='Alla ändringar sparas direkt';}
}
function renderBoard(){
 $('kanban-board').replaceChildren(...Object.entries(statuses).map(([status,label])=>{
  const column=element('section',null,'kanban-column');const bookings=data.bookings.filter(b=>b.status===status&&visibleBooking(b));column.append(element('h3',`${label} · ${bookings.length}`));
  if(!touchPlanning.matches){column.ondragover=event=>event.preventDefault();column.ondrop=event=>{event.preventDefault();const id=Number(event.dataTransfer.getData('text/plain')),b=data.bookings.find(b=>b.id===id);if(b&&b.status!==status&&canChangeStatus(b))safely(async()=>{await persist({...b,status});await load();message('Status uppdaterad.');});};}
  for(const b of bookings){const card=element('button',null,'booking-card');card.style.borderTop=`4px solid ${b.color}`;const total=b.dailyAllocations.reduce((sum,row)=>sum+row.minutes,0);card.append(element('span',b.project,'project-badge'),element('h3',b.name),element('p',`${date(b.startDate).format('ddd D MMM')}–${date(b.endDate).format('D MMM YYYY')}`),element('p',b.employeeIds.map(id=>data.employees.find(e=>e.id===id)?.name).join(', ')||'Obemannat'),element('p',`${hours(total)} planerade timmar`));card.onclick=()=>openBooking(b);card.draggable=!touchPlanning.matches&&canChangeStatus(b);card.ondragstart=event=>event.dataTransfer.setData('text/plain',b.id);column.append(card);}
  return column;
 }));
}
function renderReports(){
 const table=element('table',null,'reports-table'),head=element('thead'),row=element('tr');for(const text of ['Kollega','Kapacitet','Planerat','Avslutat','Ledigt framåt'])row.append(element('th',text));head.append(row);table.append(head);const body=element('tbody');for(const e of visiblePeople()){const c=data.capacity.find(c=>c.employeeId===e.id),tr=element('tr');for(const text of [e.name,hours(c.capacityMinutes),hours(c.scheduledMinutes),hours(c.completedMinutes),hours(c.availableMinutes)])tr.append(element('td',text));body.append(tr);}table.append(body);$('reports').replaceChildren(table);
 const projects=element('div',null,'project-progress');for(const p of data.projects.filter(p=>selectedProjects.has(p.id))){const bookings=data.bookings.filter(b=>b.calendarId===p.id&&b.status!=='CANCELLED');const done=bookings.filter(b=>b.status==='DONE').length;const section=element('div');section.append(element('h3',p.name),element('p',`${done} av ${bookings.length} aktiviteter klara`));const progress=element('progress');progress.max=bookings.length||1;progress.value=done;section.append(progress);projects.append(section);}$('reports').append(projects);
}
const allocationValues=new Map();
function dayList(first,last){const values=[];for(let day=date(first);day.isSameOrBefore(date(last),'day');day.add(1,'day'))values.push(day.clone());return values;}
function defaultMinutes(employeeId,day){if(day.isoWeekday()>5)return 0;const employee=data.employees.find(person=>person.id===employeeId);return employee?.dailyMinutes??data.settings.dailyMinutes;}
function defaultTimes(employeeId,day){const start=data.settings.workStart;const [h,m]=start.split(':').map(Number);const pause=data.settings.breakStart;const [ph,pm]=pause.split(':').map(Number);const daily=defaultMinutes(employeeId,day);let end=h*60+m+daily;if(daily>0&&h*60+m<=ph*60+pm&&end>ph*60+pm)end+=data.settings.breakMinutes;return{start,end:`${String(Math.floor(end/60)).padStart(2,'0')}:${String(end%60).padStart(2,'0')}`};}
function durationFor(start,end,day){if(!start||!end)return 0;const [sh,sm]=start.split(':').map(Number),[eh,em]=end.split(':').map(Number);const s=sh*60+sm,e=eh*60+em;if(e<=s)return 0;const [bh,bm]=data.settings.breakStart.split(':').map(Number),bs=bh*60+bm,be=bs+data.settings.breakMinutes;return Math.max(0,e-s-Math.max(0,Math.min(e,be)-Math.max(s,bs)));}
function captureAllocations(){for(const row of $('booking-allocations').querySelectorAll('tbody tr[data-employee][data-date]')){const key=`${row.dataset.employee}|${row.dataset.date}`;allocationValues.set(key,{start:row.querySelector('[data-field="start"]').value,end:row.querySelector('[data-field="end"]').value,minutes:Math.round(Number(row.querySelector('[data-field="hours"]').value||0)*60)});}}
function allocationRows(){const first=$('booking-start-date').value,last=$('booking-end-date').value;if(!first||!last||last<first)return[];const days=dayList(first,last);if(days.length>93)throw Error('En aktivitet får vara högst 93 dagar.');return [...$('booking-employees').querySelectorAll('input:checked')].flatMap(input=>days.map(day=>{const key=`${input.value}|${day.format('YYYY-MM-DD')}`,value=allocationValues.get(key)||defaultTimes(Number(input.value),day);return{employeeId:Number(input.value),date:day.format('YYYY-MM-DD'),minutes:value.minutes,startTime:value.start,endTime:value.end};}));}
function renderAllocations(booking,skipCapture=false){
 if(!skipCapture)captureAllocations();const container=$('booking-allocations'),selected=[...$('booking-employees').querySelectorAll('input:checked')].map(input=>Number(input.value)),first=$('booking-start-date').value,last=$('booking-end-date').value;
 if(!first||!last||last<first||!selected.length){container.replaceChildren();updateAllocationSummary();return;}const days=dayList(first,last);if(days.length>93){container.replaceChildren(element('p','En aktivitet får vara högst 93 dagar.','form-error'));return;}
 const table=element('table',null,'allocation-table'),head=element('thead'),hr=element('tr');for(const text of ['Person / dag','Start','Slut','Timmar'])hr.append(element('th',text));head.append(hr);table.append(head);const body=element('tbody'),editable=booking?canMoveBooking(booking):canCreateInProject(Number($('booking-project').value));
 for(const employeeId of selected){const employee=data.employees.find(person=>person.id===employeeId);for(const day of days){const key=`${employeeId}|${day.format('YYYY-MM-DD')}`,existing=booking?.dailyAllocations?.find(item=>item.employeeId===employeeId&&item.date===key.split('|')[1]);if(!allocationValues.has(key)){const d=defaultTimes(employeeId,day);allocationValues.set(key,{start:existing?.startTime||d.start,end:existing?.endTime||d.end,minutes:existing?.minutes??durationFor(d.start,d.end,day)});}const value=allocationValues.get(key),tr=element('tr');tr.dataset.employee=employeeId;tr.dataset.date=day.format('YYYY-MM-DD');const personDay=element('th',null,'allocation-person-day');personDay.append(element('span',employee?.name||'','allocation-person'),element('span',day.format('ddd D MMM'),'allocation-day'));tr.append(personDay);
 for(const [field,type] of [['start','time'],['end','time'],['hours','number']]){const td=element('td'),input=element('input');input.type=type;input.dataset.field=field;input.dataset.employee=employeeId;input.dataset.date=day.format('YYYY-MM-DD');input.setAttribute('aria-label',`${employee?.name}, ${day.format('D MMM')}, ${{start:'starttid',end:'sluttid',hours:'timmar'}[field]}`);if(field==='hours'){input.min=0;input.max=24;input.step=.5;input.value=(value.minutes/60).toString();}else{input.step='900';input.value=value[field];}input.disabled=!editable;input.oninput=()=>{const v=allocationValues.get(key);if(field==='hours'){v.minutes=Math.round(Number(input.value||0)*60);const [h,m]=v.start.split(':').map(Number);let end=h*60+m+v.minutes;const [bh,bm]=data.settings.breakStart.split(':').map(Number),bs=bh*60+bm;if(v.minutes>0&&h*60+m<=bs&&end>bs)end+=data.settings.breakMinutes;v.end=`${String(Math.floor(end/60)).padStart(2,'0')}:${String(end%60).padStart(2,'0')}`;tr.querySelector('[data-field="end"]').value=v.end;}else{v[field]=input.value;v.minutes=durationFor(v.start,v.end,day);tr.querySelector('[data-field="hours"]').value=(v.minutes/60).toString();}updateAllocationSummary();};td.append(input);tr.append(td);}body.append(tr);}}
 table.append(body);container.setAttribute('role','region');container.setAttribute('aria-label','Daglig fördelning per medarbetare');container.replaceChildren(table);updateAllocationSummary();
}
function updateAllocationSummary(){const rows=allocationRows(),total=rows.reduce((sum,row)=>sum+row.minutes,0),people=new Set(rows.map(row=>row.employeeId)).size;$('booking-duration').textContent=rows.length?`${hours(total)} totalt · ${people} ${people===1?'person':'personer'}`:'Välj datum och personal';}
function applyDefaultTimes(){captureAllocations();for(const input of $('booking-employees').querySelectorAll('input:checked'))for(const day of dayList($('booking-start-date').value,$('booking-end-date').value)){const key=`${input.value}|${day.format('YYYY-MM-DD')}`,t=defaultTimes(Number(input.value),day);allocationValues.set(key,{...t,minutes:durationFor(t.start,t.end,day)});}renderAllocations(editing,true);}
function openBooking(booking,time,group){
 editing=booking;allocationValues.clear();formError('booking-error',null);$('booking-heading').textContent=booking?'Bokning':'Ny bokning';const beginning=booking?date(booking.startDate):date(time||date()),first=beginning.format('YYYY-MM-DD'),last=booking?date(booking.endDate).format('YYYY-MM-DD'):first;
 $('booking-name').value=booking?.name||'';$('booking-description').value=booking?.description||'';$('booking-project').replaceChildren(...data.projects.filter(p=>canCreateInProject(p.id)||p.id===booking?.calendarId).map(project=>new Option(project.name,project.id)));$('booking-project').value=booking?.calendarId||data.projects.find(project=>selectedProjects.has(project.id)&&canCreateInProject(project.id))?.id||data.projects.find(project=>canCreateInProject(project.id))?.id||'';
 $('booking-status').value=booking?.status||'PLANNED';$('booking-start-date').value=first;$('booking-end-date').value=last;
 const mode=booking?(booking.sharedBlock?'COMMON':'INDIVIDUAL'):null;for(const radio of document.querySelectorAll('[name="booking-mode"]')){radio.checked=radio.value===mode;radio.disabled=!!booking;}const modeFixed=$('booking-mode-fixed');modeFixed.hidden=!booking;modeFixed.textContent=booking?(mode==='COMMON'?'Gemensamt block: teamets bokning flyttas och följs upp tillsammans.':'Separata aktiviteter: den här personens aktivitet hanteras för sig.') :'';
 $('booking-employees').replaceChildren(...data.employees.map(employee=>{const label=element('label'),input=element('input');input.type='checkbox';input.value=employee.id;input.checked=booking?booking.employeeIds.includes(employee.id):employee.id===Number(group);input.disabled=!!booking&&!canMoveBooking(booking);input.onchange=()=>renderAllocations(booking);label.append(input,document.createTextNode(employee.name));return label;}));
 const canEdit=booking?canEditBooking(booking):canCreateInProject(Number($('booking-project').value));for(const id of ['booking-name','booking-description','booking-project','booking-status'])$(id).disabled=!canEdit;for(const id of ['booking-start-date','booking-end-date','booking-default-start','booking-default-end'])$(id).disabled=booking?!canMoveBooking(booking):!canCreateInProject(Number($('booking-project').value));
 const settings=data.settings,st=settings.workStart,[sh,sm]=st.split(':').map(Number),pause=settings.breakStart.split(':').map(Number);let end=sh*60+sm+settings.dailyMinutes;if(sh*60+sm<=pause[0]*60+pause[1]&&end>pause[0]*60+pause[1])end+=settings.breakMinutes;$('booking-default-start').value=st;$('booking-default-end').value=`${String(Math.floor(end/60)).padStart(2,'0')}:${String(end%60).padStart(2,'0')}`;
 $('booking-start-date').onchange=()=>renderAllocations(booking);$('booking-end-date').onchange=()=>renderAllocations(booking);$('booking-project').onchange=()=>renderAllocations(booking);$('booking-default-start').onchange=applyDefaultTimes;$('booking-default-end').onchange=applyDefaultTimes;
 $('save-booking').hidden=!canEdit;$('delete-booking').hidden=!booking||!(isAdmin||projectAccess(booking.calendarId)?.accessLevel==='LEAD');$('delete-booking').onclick=()=>safely(async()=>{if(await confirmAction('Ta bort bokningen?',editing.name,[],'Ta bort')){await api(`/bookings/${editing.id}?version=${editing.version}`,'DELETE');$('booking-dialog').close();await load();message('Bokningen borttagen.');}});
 renderAllocations(booking);$('booking-dialog').showModal();
}
$('apply-default-times').onclick=applyDefaultTimes;
$('booking-form').onsubmit=async event=>{event.preventDefault();if(busy)return;busy=true;$('save-booking').disabled=true;formError('booking-error',null);try{const first=$('booking-start-date').value,last=$('booking-end-date').value;if(!first||!last||last<first)throw Error('Ange ett giltigt start- och slutdatum.');const rows=allocationRows(),employeeIds=[...$('booking-employees').querySelectorAll('input:checked')].map(input=>Number(input.value)),mode=document.querySelector('[name="booking-mode"]:checked')?.value;if(!employeeIds.length)throw Error('Välj minst en person.');if(!editing&&!mode)throw Error('Välj Gemensamt block eller Separata aktiviteter.');if(!editing&&mode==='INDIVIDUAL'&&employeeIds.length>50)throw Error('Välj högst 50 personer.');if(rows.some(row=>row.minutes<0||row.minutes>1440||row.minutes>0&&durationFor(row.startTime,row.endTime,date(row.date))!==row.minutes))throw Error('Kontrollera starttid, sluttid och timmar för varje dag.');if(rows.reduce((sum,row)=>sum+row.minutes,0)<30)throw Error('Ange minst 0,5 arbetstimme totalt.');const from=moment.tz(first,zone).startOf('day'),to=moment.tz(last,zone).add(1,'day').startOf('day'),common={name:$('booking-name').value,description:$('booking-description').value,calendarId:Number($('booking-project').value),status:$('booking-status').value,start:from.toISOString(),end:to.toISOString(),allowConflict:false};
 if(editing)await persist({...editing,...common,employeeIds,dailyAllocations:rows,sharedBlock:editing.sharedBlock});else if(mode==='COMMON')await persist({...common,employeeIds,dailyAllocations:rows,sharedBlock:true});else await persistBatch(employeeIds.map(employeeId=>({...common,employeeIds:[employeeId],dailyAllocations:rows.filter(row=>row.employeeId===employeeId),sharedBlock:false})));$('booking-dialog').close();await load();message('Bokningen sparad.');}catch(error){formError('booking-error',error);if(error.code==='STALE_BOOKING')await load();}finally{busy=false;$('save-booking').disabled=false;}};for(const button of document.querySelectorAll('[data-close-dialog]'))button.onclick=()=>$(button.dataset.closeDialog).close();
for(const button of document.querySelectorAll('[data-view]'))button.onclick=()=>{for(const tab of document.querySelectorAll('[data-view]'))tab.classList.toggle('selected',tab===button);for(const view of ['timeline','board','reports'])$(`${view}-panel`).hidden=view!==button.dataset.view;if(button.dataset.view==='timeline')timeline?.redraw();};
$('previous-period').onclick=()=>safely(async()=>{start.subtract(days,'days');await load();});$('next-period').onclick=()=>safely(async()=>{start.add(days,'days');await load();});$('today').onclick=()=>safely(async()=>{start=date().startOf('day');await load();});$('range-days').onchange=()=>safely(async()=>{days=Number($('range-days').value);await load();});$('employee-filter').onchange=renderViews;
$('zoom-in').onclick=()=>timeline?.zoomIn(.5);$('zoom-out').onclick=()=>timeline?.zoomOut(.5);
touchPlanning.addEventListener('change',()=>{if(data)renderViews();});
$('add-booking')?.addEventListener('click',()=>openBooking());
function showWorkday(){const s=data.settings;$('daily-hours').value=s.dailyMinutes/60;$('work-start').value=s.workStart;$('break-start').value=s.breakStart;$('break-minutes').value=s.breakMinutes;formError('workday-error',null);$('employee-settings').replaceChildren(...data.employees.map(e=>{const label=element('label',e.name),input=element('input');input.type='number';input.min=.5;input.max=16;input.step=.5;input.placeholder='Företagets standard';input.value=e.dailyMinutes==null?'':e.dailyMinutes/60;input.dataset.employeeId=e.id;label.append(input);return label;}));$('workday-dialog').showModal();}
$('workday-button')?.addEventListener('click',showWorkday);
$('add-person').onclick=async()=>{try{await api('/employees','POST',{name:$('new-person-name').value,dailyMinutes:null});$('new-person-name').value='';await load();showWorkdayFields();}catch(error){formError('workday-error',error);}};
function showWorkdayFields(){ $('employee-settings').replaceChildren(...data.employees.map(e=>{const label=element('label',e.name),input=element('input');input.type='number';input.min=.5;input.max=16;input.step=.5;input.value=e.dailyMinutes==null?'':e.dailyMinutes/60;input.dataset.employeeId=e.id;label.append(input);return label;})); }
$('workday-form').onsubmit=async event=>{event.preventDefault();try{await api('/settings','PUT',{dailyMinutes:Math.round(Number($('daily-hours').value)*60),workStart:$('work-start').value,breakStart:$('break-start').value,breakMinutes:Number($('break-minutes').value)});for(const input of $('employee-settings').querySelectorAll('input')){const e=data.employees.find(e=>e.id===Number(input.dataset.employeeId));const dailyMinutes=input.value===''?null:Math.round(Number(input.value)*60);if(dailyMinutes!==e.dailyMinutes)await api(`/employees/${e.id}`,'PUT',{name:e.name,dailyMinutes});}$('workday-dialog').close();await load();message('Arbetstiden har sparats.');}catch(error){formError('workday-error',error);}};
$('new-project')?.addEventListener('click',()=>{formError('project-error',null);$('project-name').value='';$('project-dialog').showModal();});
$('project-form').onsubmit=async event=>{event.preventDefault();try{const result=await api('/projects','POST',{name:$('project-name').value,color:$('project-color').value});selectedProjects.add(result.id);$('project-dialog').close();await load();message('Projektet skapat.');}catch(error){formError('project-error',error);}};
function renderEmployeeColors(){let section=$('employee-color-settings');if(!section){section=element('section',null,'employee-color-settings');$('employee-settings').insertAdjacentElement('afterend',section);}const heading=element('h3','Medarbetarfärger'),help=element('p','Aktiviteter får medarbetarens färg. Överkanten i tidslinjen visar projektets färg.','field-help');const rows=data.employees.map(employee=>{const label=element('label',null,'employee-color-row'),name=element('span',employee.name),input=element('input');input.type='color';input.value=employee.color||'#64748B';input.dataset.employeeColor=employee.id;input.setAttribute('aria-label',`Färg för ${employee.name}`);label.append(name,input);return label;});section.replaceChildren(heading,help,...rows);}
$('workday-button')?.addEventListener('click',renderEmployeeColors);
const addPersonButton=$('add-person'),originalAddPerson=addPersonButton.onclick;addPersonButton.onclick=async event=>{await originalAddPerson.call(addPersonButton,event);renderEmployeeColors();};
const workdayForm=$('workday-form'),originalSaveWorkday=workdayForm.onsubmit;workdayForm.onsubmit=async event=>{event.preventDefault();try{for(const employee of data.employees){const colorInput=$('employee-color-settings')?.querySelector(`[data-employee-color="${employee.id}"]`),color=colorInput?.value.toUpperCase();if(color&&color!==employee.color?.toUpperCase())await api(`/employees/${employee.id}`,'PUT',{name:employee.name,dailyMinutes:employee.dailyMinutes,color});}await originalSaveWorkday.call(workdayForm,event);}catch(error){formError('workday-error',error);}};
window.addEventListener('unhandledrejection',event=>{event.preventDefault();message(event.reason?.message||'Planeringen kunde inte laddas.',true);});
$('range-days').value=String(days);
safely(load);

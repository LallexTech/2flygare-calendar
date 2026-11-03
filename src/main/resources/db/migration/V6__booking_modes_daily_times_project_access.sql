alter table activity add column shared_block boolean not null default false;
update activity a set shared_block=true
where (select count(*) from activity_employee ae where ae.activity_id=a.id)>1;

alter table employee add column user_id bigint unique references app_user(id) on delete set null;
alter table activity_work_day add column start_time time not null default time '07:00';
alter table activity_work_day add column end_time time not null default time '16:00';

update activity_work_day w
set start_time=case
        when (a.starts_at at time zone 'Europe/Stockholm')::date=w.work_date
             and (a.starts_at at time zone 'Europe/Stockholm')::time<>time '00:00'
        then (a.starts_at at time zone 'Europe/Stockholm')::time
        else settings.work_start::time end,
    end_time=case
        when (a.ends_at at time zone 'Europe/Stockholm')::date=w.work_date
             and (a.ends_at at time zone 'Europe/Stockholm')::time<>time '00:00'
        then (a.ends_at at time zone 'Europe/Stockholm')::time
        else (settings.work_start::time + (w.minutes + case
            when w.minutes > greatest(0, extract(epoch from (settings.break_start::time-settings.work_start::time))/60)::integer
                 then settings.break_minutes else 0 end) * interval '1 minute')::time end
from activity a, planning_settings settings
where w.activity_id=a.id;

create table project_user_access (
    user_id bigint not null references app_user(id) on delete cascade,
    calendar_id bigint not null references calendar(id) on delete cascade,
    access_level varchar(16) not null check (access_level in ('VIEWER','WORKER','LEAD')),
    can_move_own boolean not null default false,
    primary key(user_id,calendar_id)
);
create index project_user_access_calendar on project_user_access(calendar_id,access_level);

insert into project_user_access(user_id,calendar_id,access_level)
select u.id,c.id,'VIEWER' from app_user u cross join calendar c
on conflict (user_id,calendar_id) do nothing;

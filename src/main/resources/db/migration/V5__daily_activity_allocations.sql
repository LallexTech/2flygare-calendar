create table activity_work_day (
    activity_id bigint not null references activity(id) on delete cascade,
    employee_id bigint not null references employee(id),
    work_date date not null,
    minutes integer not null check (minutes between 0 and 1440),
    primary key(activity_id, employee_id, work_date)
);
create index activity_work_day_employee_date on activity_work_day(employee_id, work_date);

insert into activity_work_day(activity_id, employee_id, work_date, minutes)
select a.id, ae.employee_id, dates.work_date,
       (greatest(0, floor(extract(epoch from (
           least(a.ends_at, (dates.work_date + settings.break_start::time) at time zone 'Europe/Stockholm')
           - greatest(a.starts_at, dates.work_date::timestamp at time zone 'Europe/Stockholm')
       )) / 60))
       + greatest(0, floor(extract(epoch from (
           least(a.ends_at, (dates.work_date + interval '1 day') at time zone 'Europe/Stockholm')
           - greatest(a.starts_at, (dates.work_date + settings.break_start::time
               + settings.break_minutes * interval '1 minute') at time zone 'Europe/Stockholm')
       )) / 60)))::integer
from activity a
join activity_employee ae on ae.activity_id = a.id
cross join planning_settings settings
cross join lateral generate_series(
    (a.starts_at at time zone 'Europe/Stockholm')::date,
    ((a.ends_at - interval '1 microsecond') at time zone 'Europe/Stockholm')::date,
    interval '1 day'
) as dates(work_date);

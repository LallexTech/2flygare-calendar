alter table employee add column color varchar(7);

with employee_order as (
    select id, row_number() over (order by id) - 1 as palette_index
    from employee
),
palette(palette_index, color) as (
    values
        (0, '#2563EB'),
        (1, '#0F766E'),
        (2, '#7C3AED'),
        (3, '#B45309'),
        (4, '#BE123C'),
        (5, '#15803D'),
        (6, '#0369A1'),
        (7, '#A21CAF'),
        (8, '#4D7C0F'),
        (9, '#C2410C')
)
update employee e
set color = palette.color
from employee_order ordered
join palette on palette.palette_index = ordered.palette_index % 10
where e.id = ordered.id;

alter table employee alter column color set default '#2563EB';
alter table employee alter column color set not null;
alter table employee add constraint employee_color_format check (color ~ '^#[0-9A-Fa-f]{6}$');

{{
  config(
    materialized="table",
    tags=["marts", "gold", "dimension", "date"],
    description="Date dimension - one row per calendar date with date attributes."
  )
}}
with date_bounds as (
    select
        min(date(created_at)) as min_date,
        max(date(created_at)) as max_date
    from {{ ref('stg_transactions') }}
),
dates as (
    select
        generate_series(
            (select min_date from date_bounds),
            (select max_date from date_bounds),
            interval '1 day'
        )::date as date_value
)
select
    {{ dbt_utils.surrogate_key(['date_value']) }} as date_key,
    d.date_value,
    extract(year from d.date_value)::int as year,
    extract(month from d.date_value)::int as month,
    extract(day from d.date_value)::int as day,
    extract(week from d.date_value)::int as week,
    extract(dow from d.date_value)::int as day_of_week,
    to_char(d.date_value, 'Day') as day_name,
    to_char(d.date_value, 'Month') as month_name,
    case when extract(dow from d.date_value) in (0, 6) then true else false end as is_weekend,
    current_timestamp as updated_at
from dates d

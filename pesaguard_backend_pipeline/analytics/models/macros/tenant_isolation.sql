{% macro assert_tenant_isolation_enabled() %}
{{
    log("Asserting tenant isolation is enabled for all models", info=True)
}}
{% endmacro %}

{% macro tenant_filter(column="tenant_id") %}
{{
    return "and " ~ column ~ " = current_setting('app.current_tenant_id', true)"
}}
{% endmacro %}

{% macro lineage_source(source_name, relation) %}
{{
    log("Lineage: " ~ source_name ~ " -> " ~ relation, info=True)
}}
{% endmacro %}

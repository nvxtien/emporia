#!/bin/bash
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE emporia_authentication;
    CREATE DATABASE emporia_static_data;
    CREATE DATABASE emporia_user_preferences;
    CREATE DATABASE emporia_order_management;
    CREATE DATABASE emporia_execution;
    CREATE DATABASE emporia_portfolio;
EOSQL

#!/usr/bin/env bash

set -e

function script_dir {
  val="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
  echo "$val"
}

base=$(script_dir)

echo
echo "Installing Proletarian Database"
echo "= = ="
echo

if [ -z ${DATABASE_NAME+x} ]; then
  database=proletarian
  echo "DATABASE_NAME is not set. Using: $database."
  export DATABASE_NAME=$database
else
  database=$DATABASE_NAME
fi

if [ -z ${CREATE_DATABASE+x} ]; then
  CREATE_DATABASE="on"
fi

create_database=true
if [ "$CREATE_DATABASE" = "off" ] ; then
  create_database=false
fi

if [ -z ${PGOPTIONS+x} ]; then
  export PGOPTIONS='-c client_min_messages=warning'
fi

function create-user {
  echo "» proletarian role"
  psql postgres -q -v ON_ERROR_STOP=1 -f $base/roles.sql
}

function create-database {
  echo "» $database database"
  createdb $database
}

function create-tables {
  echo "» proletarian schema"
  echo "» proletarian.job table"
  echo "» proletarian.archived_job table"
  echo "» proletarian.job_queue_process_at index"
  psql $database -q -v ON_ERROR_STOP=1 -f $base/tables.sql
}

function grant-privileges {
  echo "» schema privileges"
  echo "» table privileges"
  psql $database -q -v ON_ERROR_STOP=1 -f $base/privileges.sql
}


echo "Creating User"
echo "- - -"
create-user
echo

echo "Creating Database"
echo "- - -"
if [ "$create_database" = true ] ; then
  create-database
else
  echo "Database creation is deactivated. Not creating the database."
fi
echo

echo "Creating Schema, Tables, and Index"
create-tables
echo

echo "Granting Privileges"
echo "- - -"
grant-privileges
echo

echo "= = ="
echo "Done Installing Proletarian Database"
echo

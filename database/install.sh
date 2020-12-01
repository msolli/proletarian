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
  base=$(script_dir)

  echo "» proletarian role"
  psql postgres -q -f $base/roles/proletarian.sql
}

function create-database {
  echo "» $database database"
  createdb $database
}

function create-schema {
  echo "» proletarian schema"
  psql $database -q -f $base/schema/proletarian.sql
}

function create-tables {
  echo "» proletarian.job table"
  psql $database -q -f $base/tables/job.sql
  echo "» proletarian.archived_job table"
  psql $database -q -f $base/tables/archived_job.sql
}

function create-index {
  echo "» proletarian.job_queue_process_at index"
  psql $database -q -f $base/indexes/job_queue_process_at.sql
}

function grant-privileges {
  echo "» schema privileges"
  psql $database -q -f $base/privileges/schema.sql

  echo "» table privileges"
  psql $database -q -f $base/privileges/tables.sql
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

echo "Creating Schema"
echo "- - -"
create-schema
echo

echo "Creating Tables"
echo "- - -"
create-tables
echo

echo "Creating Index"
echo "- - -"
create-index
echo

echo "Granting Privileges"
echo "- - -"
grant-privileges
echo

echo "= = ="
echo "Done Installing Proletarian Database"
echo

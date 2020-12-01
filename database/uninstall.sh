#!/usr/bin/env bash

set -e

function script_dir {
  val="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
  echo "$val"
}

base=$(script_dir)

echo
echo "Uninstalling Proletarian Database"
echo "= = ="
echo

if [ -z ${DATABASE_NAME+x} ]; then
  database=proletarian
  echo "DATABASE_NAME is not set. Using: $database."
else
  database=$DATABASE_NAME
fi
echo

if [ -z ${PGOPTIONS+x} ]; then
  export PGOPTIONS='-c client_min_messages=warning'
fi

function delete-user {
  echo "» proletarian user"
  psql postgres -P pager=off -q -c "DROP ROLE IF EXISTS proletarian;"
}

function delete-database {
  echo "» $database database"
  psql postgres -P pager=off -q -c "DROP DATABASE IF EXISTS $database;"
}

echo "Deleting database"
echo "- - -"
delete-database
echo

echo "Deleting database user"
echo "- - -"
delete-user

echo

echo "= = ="
echo "Done Uninstalling Proletarian Database"
echo

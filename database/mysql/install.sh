#!/usr/bin/env bash

set -e

function script_dir {
  val="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
  echo "$val"
}

base=$(script_dir)

#
# Please change these variables to suit your setup.
#
hostname=localhost
port=3306
username=root
password=password
#
#
#

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

function create-database {
  echo "» $database database"
  mysql -h $hostname --port $port -u$username -p"$password" --protocol tcp --skip-ssl < $base/db.sql
}

function create-user {
  echo "» proletarian user"
  mysql -h $hostname --port $port -u$username -p"$password" --protocol tcp --skip-ssl -D $database < $base/user.sql
}

function create-tables {
  echo "» proletarian.job table"
  echo "» proletarian.archived_job table"
  mysql -h $hostname --port $port -u$username -p"$password" --protocol tcp --skip-ssl -D $database < $base/tables.sql
}

function grant-privileges {
  echo "» table privileges"
  mysql -h $hostname --port $port -u$username -p"$password"  --protocol tcp --skip-ssl -D $database < $base/privileges.sql
}

echo "Creating Database"
echo "- - -"
if [ "$create_database" = true ] ; then
  create-database
else
  echo "Database creation is deactivated. Not creating the database."
fi
echo

echo "Creating User"
echo "- - -"
create-user
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

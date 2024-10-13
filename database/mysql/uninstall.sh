#!/usr/bin/env bash

set -e

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
#
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

function delete-user {
  echo "» proletarian user"
  mysql -h $hostname --port $port -u$username -p"$password" --protocol tcp --skip-ssl -e "DROP USER IF EXISTS proletarian;"
}

function delete-database {
  echo "» $database database"
  mysql -h $hostname --port $port -u$username -p"$password" --protocol tcp --skip-ssl -e "DROP DATABASE IF EXISTS $database;"
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

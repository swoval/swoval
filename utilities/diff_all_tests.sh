#!/bin/bash

PWD=`pwd`
file1="$(PWD)/$1"
file2="$(PWD)/$2"

echo $file1
echo $file2
tempdir=`mktemp -d`
echo $tempdir
transformed1="$tempdir/$1"
transformed2="$tempdir/$2"
echo $transformed1
ggrep -Po "^\+.+?[a-zA-Z1-9.]+" $file1 | sort -k 2 > $transformed1
ggrep -Po "^\+.+?[a-zA-Z1-9.]+" $file2 | sort -k 2 > $transformed2
icdiff $transformed1 $transformed2

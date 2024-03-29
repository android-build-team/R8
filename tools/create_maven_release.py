#!/usr/bin/env python
# Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
# for details. All rights reserved. Use of this source code is governed by a
# BSD-style license that can be found in the LICENSE file.

import argparse
import gradle
import hashlib
from os import makedirs
from os.path import join
from shutil import copyfile, make_archive, rmtree
import subprocess
import sys
from string import Template
import tempfile
import utils

DEPENDENCYTEMPLATE = Template(
"""
    <dependency>
        <groupId>$group</groupId>
        <artifactId>$artifact</artifactId>
        <version>$version</version>
    </dependency>""")

LICENSETEMPLATE = Template(
"""
    <license>
      <name>$name</name>
      <url>$url</url>
      <distribution>repo</distribution>
    </license>""")

POMTEMPLATE = Template(
"""<project
    xmlns="http://maven.apache.org/POM/4.0.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.android.tools</groupId>
  <artifactId>r8</artifactId>
  <version>$version</version>
  <name>D8 dexer and R8 shrinker</name>
  <description>
  D8 dexer and R8 shrinker.
  </description>
  <url>http://r8.googlesource.com/r8</url>
  <inceptionYear>2016</inceptionYear>
  <licenses>
    <license>
      <name>BSD-3-Clause</name>
      <url>https://opensource.org/licenses/BSD-3-Clause</url>
      <distribution>repo</distribution>
    </license>$library_licenses
  </licenses>
  <dependencies>$dependencies
  </dependencies>
  <developers>
    <developer>
      <name>The Android Open Source Project</name>
    </developer>
  </developers>
  <scm>
    <connection>
      https://r8.googlesource.com/r8.git
    </connection>
    <url>
      https://r8.googlesource.com/r8
    </url>
  </scm>
</project>
""")

def parse_options(argv):
  result = argparse.ArgumentParser()
  result.add_argument('--out', help='The zip file to output')
  result.add_argument('--r8lib', action='store_true',
                      help='Build r8 with dependencies included shrunken')
  return result.parse_args(argv)

def determine_version():
  version_file = join(
      utils.SRC_ROOT, 'com', 'android', 'tools', 'r8', 'Version.java')
  with open(version_file, 'r') as file:
    for line in file:
      if 'final String LABEL ' in line:
        result = line[line.find('"') + 1:]
        result = result[:result.find('"')]
        return result
  raise Exception('Unable to determine version.')

def generate_library_licenses():
  artifact_prefix = '- artifact: '
  license_prefix = 'license: '
  licenses = []
  license_url_prefix = 'licenseUrl: '
  license_urls = []
  with open('LIBRARY-LICENSE', 'r') as file:
    name = None
    url = None
    for line in file:
      trimmed = line.strip()
      # Collect license name and url for each artifact. They must come in
      # pairs for each artifact.
      if trimmed.startswith(artifact_prefix):
        assert not name
        assert not url
      if trimmed.startswith(license_prefix):
        name = trimmed[len(license_prefix):]
      if trimmed.startswith(license_url_prefix):
        url = trimmed[len(license_url_prefix):]
      # Licenses come in name/url pairs. When both are present add pair
      # to collected licenses if either name or url has not been recorded yet,
      # as some licenses with slightly different names point to the same url.
      if name and url:
        if (not name in licenses) or (not url in license_urls):
          licenses.append(name)
          license_urls.append(url)
        name = None
        url = None
      assert len(licenses) == len(license_urls)
  result = ''
  for i in range(len(licenses)):
    name = licenses[i]
    url = license_urls[i]
    result += LICENSETEMPLATE.substitute(name=name, url=url)
  return result


# Generate the dependencies block for the pom file.
#
# We ask gradle to list all dependencies. In that output
# we locate the runtimeClasspath block for 'main' which
# looks something like:
#
# runtimeClasspath - Runtime classpath of source set 'main'.
# +--- net.sf.jopt-simple:jopt-simple:4.6
# +--- com.googlecode.json-simple:json-simple:1.1
# +--- com.google.guava:guava:23.0
# +--- it.unimi.dsi:fastutil:7.2.0
# +--- org.ow2.asm:asm:6.0
# +--- org.ow2.asm:asm-commons:6.0
# |    \--- org.ow2.asm:asm-tree:6.0
# |         \--- org.ow2.asm:asm:6.0
# +--- org.ow2.asm:asm-tree:6.0 (*)
# +--- org.ow2.asm:asm-analysis:6.0
# |    \--- org.ow2.asm:asm-tree:6.0 (*)
# \--- org.ow2.asm:asm-util:6.0
#      \--- org.ow2.asm:asm-tree:6.0 (*)
#
# We filter out the repeats that are marked by '(*)'.
#
# For each remaining line, we remove the junk at the start
# in chunks. As an example:
#
# '  |    \--- org.ow2.asm:asm-tree:6.0  '  --strip-->
# '|    \--- org.ow2.asm:asm-tree:6.0'  -->
# '\--- org.ow2.asm:asm-tree:6.0'  -->
# 'org.ow2.asm:asm-tree:6.0'
#
# The end result is the dependency we are looking for:
#
# groupId: org.ow2.asm
# artifact: asm-tree
# version: 6.0
def generate_dependencies():
  dependencies = gradle.RunGradleGetOutput(['dependencies'])
  dependency_lines = []
  collect = False
  for line in dependencies.splitlines():
    if 'runtimeClasspath' in line and "'main'" in line:
      collect = True
      continue
    if collect:
      if not len(line) == 0:
        if not '(*)' in line:
          trimmed = line.strip()
          while trimmed.find(' ') != -1:
            trimmed = trimmed[trimmed.find(' ') + 1:].strip()
          if not trimmed in dependency_lines:
            dependency_lines.append(trimmed)
      else:
        break
  result = ''
  for dep in dependency_lines:
    components = dep.split(':')
    assert len(components) == 3
    group = components[0]
    artifact = components[1]
    version = components[2]
    result += DEPENDENCYTEMPLATE.substitute(
        group=group, artifact=artifact, version=version)
  return result

def write_pom_file(version, pom_file, exclude_dependencies):
  dependencies = "" if exclude_dependencies else generate_dependencies()
  library_licenses = generate_library_licenses() if exclude_dependencies else ""
  version_pom = POMTEMPLATE.substitute(
      version=version, dependencies=dependencies, library_licenses=library_licenses)
  with open(pom_file, 'w') as file:
    file.write(version_pom)

def hash_for(file, hash):
  with open(file, 'rb') as f:
    while True:
      # Read chunks of 1MB
      chunk = f.read(2 ** 20)
      if not chunk:
        break
      hash.update(chunk)
  return hash.hexdigest()

def write_md5_for(file):
  hexdigest = hash_for(file, hashlib.md5())
  with (open(file + '.md5', 'w')) as file:
    file.write(hexdigest)

def write_sha1_for(file):
  hexdigest = hash_for(file, hashlib.sha1())
  with (open(file + '.sha1', 'w')) as file:
    file.write(hexdigest)

def run(out, is_r8lib=False):
  if out == None:
    print 'Need to supply output zip with --out.'
    exit(1)
  # Build the R8 no deps artifact.
  if not is_r8lib:
    gradle.RunGradleExcludeDeps([utils.R8])
  else:
    gradle.RunGradle([utils.R8LIB, '-Pno_internal'])
  # Create directory structure for this version.
  version = determine_version()
  with utils.TempDir() as tmp_dir:
    version_dir = join(tmp_dir, utils.get_maven_path('r8', version))
    makedirs(version_dir)
    # Write the pom file.
    pom_file = join(version_dir, 'r8-' + version + '.pom')
    write_pom_file(version, pom_file, is_r8lib)
    # Copy the jar to the output.
    target_jar = join(version_dir, 'r8-' + version + '.jar')
    copyfile(utils.R8LIB_JAR if is_r8lib else utils.R8_JAR, target_jar)
    # Create check sums.
    write_md5_for(target_jar)
    write_md5_for(pom_file)
    write_sha1_for(target_jar)
    write_sha1_for(pom_file)
    # Zip it up - make_archive will append zip to the file, so remove.
    assert out.endswith('.zip')
    base_no_zip = out[0:len(out)-4]
    make_archive(base_no_zip, 'zip', tmp_dir)

def main(argv):
  options = parse_options(argv)
  out = options.out
  run(out, options.r8lib)

if __name__ == "__main__":
  exit(main(sys.argv[1:]))

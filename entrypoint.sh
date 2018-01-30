#!/bin/bash

set -euxo pipefail

# get gid of docker socket file
SOCK_DOCKER_GID=$(ls -ng /var/run/docker.sock | cut -f3 -d' ')

# get group of docker inside container
CUR_DOCKER_GID=$(getent group docker | cut -f3 -d: || true)

# if they don't match, adjust
if [ ! -z "${SOCK_DOCKER_GID}" -a "${SOCK_DOCKER_GID}" != "${CUR_DOCKER_GID}" ]; then
  groupmod -g ${SOCK_DOCKER_GID} docker
fi

if ! groups jenkins | grep -q docker; then
  usermod -aG docker jenkins
fi

mkdir -p "${JENKINS_HOME}"/.ssh/ || true
if [[ -f /opt/known_hosts ]]; then
  cp /opt/known_hosts "${JENKINS_HOME}"/.ssh/ -a
fi
if [[ -f /opt/id_rsa ]]; then
  cp /opt/id_rsa "${JENKINS_HOME}"/.ssh/ -a
fi

chown jenkins:jenkins "${JENKINS_HOME}"/.ssh -R
chmod 600 "${JENKINS_HOME}"/.ssh -R

sed -E '1s,(.*)[[:space:]]*$,\1x,g' -i /usr/local/bin/jenkins.sh

# drop access to jenkins user and run jenkins entrypoint
exec gosu jenkins /usr/local/bin/tini -- /usr/local/bin/jenkins.sh "$@"
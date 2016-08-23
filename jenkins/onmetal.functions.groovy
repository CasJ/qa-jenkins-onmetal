#!/usr/bin/env groovy

// Any command placed at the root of the file get executed during the load operation
echo "Loading external functions..."


def wait_for_ping(host_ip) {
    
    echo "Waiting for the host with IP:${host_ip} to become online."
    def response
    def current_time
    int timeout = 360
    def initial_time = sh returnStdout: true, script: 'date +%s'
    waitUntil {
        current_time = sh returnStdout: true, script: 'date +%s'
        if (current_time.toInteger() - initial_time.toInteger() > timeout) {
            error "The host did not respond to ping within the timeout of ${timeout} seconds"
        }
        response = sh returnStatus: true, script: "ping -q -c 1 ${host_ip}"
        return (response == 0) 
    }
    
}


def onmetal_provision(playbooks_path) {

    // Spin onMetal Server
    sh """
    cd ${playbooks_path}
    sudo ansible-playbook build_onmetal.yaml --tags 'iad'
    """

    // Verify onMetal server data
    sh """
    cd ${playbooks_path}
    sudo ansible-playbook -i hosts get_onmetal_facts.yaml --tags 'iad'
    """

    // Get server IP address
    String hosts = readFile("${playbooks_path}hosts")
    def ip = hosts.substring(hosts.indexOf('=')+1)

    // Wait for server to become active
    wait_for_ping(ip)

    // Prepare OnMetal server, retry up to 5 times for the command to work
    retry(5) {
        sh """
        cd ${playbooks_path}
        sudo ansible-playbook -i hosts prepare_onmetal.yaml
        """
    }

    // Apply CPU fix - will restart server (~5 min)
    sh """
    cd ${playbooks_path}
    sudo ansible-playbook -i hosts set_onmetal_cpu.yaml
    """

    // Wait for the server to come back online
    wait_for_ping(ip)

    // Add the onMetal host to the known_hosts
    sh """
    ssh -o StrictHostKeyChecking=no root@${ip} 'echo "server added to known_hosts"'
    """

    return (ip)

}


def vm_provision(playbooks_path) {

    // Configure VMs onMetal server
    sh """
    cd ${playbooks_path}
    sudo ansible-playbook -i hosts configure_onmetal.yaml
    """

    // Create VMs where OSA will be deployed
    sh """
    cd ${playbooks_path}
    sudo ansible-playbook -i hosts create_lab.yaml
    """

}


// The external code must return it's contents as an object
return this;


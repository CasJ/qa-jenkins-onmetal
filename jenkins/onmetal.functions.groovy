// Any command placed at the root of the file get executed during the load operation
echo "Loading onMetal functions..."


def wait_for_ping(ip) {
    
    echo "Waiting for the host with IP:${ip} to become online."
    def response
    def current_time
    int timeout = 360
    def initial_time = sh returnStdout: true, script: 'date +%s'
    waitUntil {
        current_time = sh returnStdout: true, script: 'date +%s'
        if (current_time.toInteger() - initial_time.toInteger() > timeout) {
            error "The host did not respond to ping within the timeout of ${timeout} seconds"
        }
        response = sh returnStatus: true, script: "ping -q -c 1 ${ip}"
        return (response == 0) 
    }
    
}


def onmetal_provision(home) {

    def ip
    
    // Spin onMetal Server
    sh """
    cd ${home}
    sudo ansible-playbook build_onmetal.yaml --tags 'iad'
    """
    
    // Verify onMetal server data
    sh """
    cd ${home}
    sudo ansible-playbook -i hosts get_onmetal_facts.yaml --tags 'iad'
    """

    // Get server IP address
    String hosts = readFile("${home}hosts")
    ip = hosts.substring(hosts.indexOf('=')+1)
    
    // Wait for server to become active
    wait_for_ping(ip)

    // Prepare OnMetal server, retry up to 5 times for the command to work
    retry(5) {
        sh """
        cd ${home}
        sudo ansible-playbook -i hosts prepare_onmetal.yaml
        """
    }

    // Apply CPU fix - will restart server (~5 min)
    sh """
    cd ${home}
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


def vm_provision(home) {

    // Configure VMs onMetal server
    sh """
    cd ${home}
    sudo ansible-playbook -i hosts configure_onmetal.yaml
    """

    // Create VMs where OSA will be deployed
    sh """
    cd ${home}
    sudo ansible-playbook -i hosts create_lab.yaml
    """

}


def vm_preparation_for_osa(home) {

    // Prepare each VM for OSA installation
    sh """
    cd ${home}
    sudo ansible-playbook -i hosts prepare_for_osa.yaml
    """

}


def deploy_openstack(home) {

    sh """
    cd ${home}
    sudo ansible-playbook -i hosts deploy_osa.yaml
    """

}


def configure_tempest(ip) {

    // Install Tempest
    sh """
    ssh root@${ip} '''
    git clone https://github.com/openstack/tempest.git /root/tempest
    cd /root/tempest/
    sudo pip install -r requirements.txt
    cd /root/tempest/etc/
    wget https://raw.githubusercontent.com/CasJ/openstack_one_node_ci/master/tempest.conf
    '''
    """

    // Get the tempest config file generated by the OSA deployment
    sh """
    ssh root@${ip} '''
    scp infra01_utility:/opt/tempest_untagged/etc/tempest.conf /root/tempest/etc/tempest.conf.osa
    '''
    """

    // Configure tempest based on the OSA deployment
    sh """
    ssh root@${ip} '''
    keys="admin_password image_ref image_ref_alt uri uri_v3 public_network_id reseller_admin_role"
    for key in \$keys
    do
        a="\${key} ="
        sed -ir "s|\$a.*|\$a|g" /root/tempest/etc/tempest.conf
        b=`cat /root/tempest/etc/tempest.conf.osa | grep "\$a"`
        sed -ir "s|\$a|\$b|g" /root/tempest/etc/tempest.conf
    done
    '''
    """

}


run_tempest_smoke_tests(ip) {

    // Run the tests and store the results in ~/subunit/before
    sh """
    ssh root@${ip} '''
    mkdir /root/subunit
    cd /root/tempest/
    testr init
    stream_id=`cat .testrepository/next-stream`
    ostestr --no-slowest --regex smoke
    cp .testrepository/\$stream_id /root/subunit/before
    '''
    """

}


// !!Important Boilerplate!!
// The external code must return it's contents as an object
return this;


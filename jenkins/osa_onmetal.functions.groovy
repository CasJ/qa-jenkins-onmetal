#!/usr/bin/env groovy
import static common.groovy.*

// Any command placed at the root of the file get executed during the load operation
echo "Loading external functions for an onMetal host..."


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
    String ip = hosts.substring(hosts.indexOf('=')+1)

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


def vm_preparation_for_osa(playbooks_path) {

    // Prepare each VM for OSA installation
    sh """
    cd ${playbooks_path}
    sudo ansible-playbook -i hosts prepare_for_osa.yaml
    """

}


def deploy_openstack(playbooks_path) {

    sh """
    cd ${playbooks_path}
    sudo ansible-playbook -i hosts deploy_osa.yaml
    """

}


def configure_tempest(host_ip) {

    // Install Tempest
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    git clone https://github.com/openstack/tempest.git /root/tempest
    cd /root/tempest/
    sudo pip install -r requirements.txt
    cd /root/tempest/etc/
    wget https://raw.githubusercontent.com/CasJ/openstack_one_node_ci/master/tempest.conf
    '''
    """

    // Get the tempest config file generated by the OSA deployment
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    scp infra01_utility:/opt/tempest_untagged/etc/tempest.conf /root/tempest/etc/tempest.conf.osa
    '''
    """

    // Configure tempest based on the OSA deployment
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
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


def run_tempest_smoke_tests(host_ip) {

    // Run the tests and store the results in ~/subunit/before
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    mkdir /root/subunit
    cd /root/tempest/
    testr init
    stream_id=`cat .testrepository/next-stream`
    ostestr --no-slowest --regex smoke
    cp .testrepository/\$stream_id /root/subunit/before
    '''
    """

}


// The external code must return it's contents as an object
return this;


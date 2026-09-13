*** Settings ***
Library    Collections
Library    RequestsLibrary
Library    Process

*** Variables ***
${BASE_URL}    http://127.0.0.1

*** Test Cases ***
All services are up and running
    FOR    ${service}    ${path}    IN
    ...    environment-monitor    /live
    ...    occupancy-monitor    /live
    ...    alert-notifier    /live
    ...    sensors-data-collector    /live
    ...    home-dashboard    /
        ${headers}=    Create Dictionary    Host=${service}.local
        Create Session    ${service}    ${BASE_URL}    headers=${headers}
        ${response}=    GET On Session    ${service}    ${path}
        Should Be Equal As Integers    ${response.status_code}    200
    END

All services are ready
    FOR    ${service}    ${path}    IN
    ...    environment-monitor    /ready
    ...    occupancy-monitor    /ready
    ...    alert-notifier    /ready
    ...    sensors-data-collector    /ready
    ...    home-dashboard    /
        ${headers}=    Create Dictionary    Host=${service}.local
        Create Session    ${service}    ${BASE_URL}    headers=${headers}
        ${response}=    GET On Session    ${service}    ${path}
        Should Be Equal As Integers    ${response.status_code}    200
    END

Sensor data is transferred from MQTT to Kafka
    ${consumer}=    Start Process
    ...    kubectl
    ...    exec
    ...    deployment/kafka
    ...    --
    ...    /opt/kafka/bin/kafka-console-consumer.sh
    ...    --bootstrap-server
    ...    kafka:9092
    ...    --topic
    ...    sensor-data
    ...    --group
    ...    robot-e2e-test
    ...    --from-beginning
    ...    --timeout-ms
    ...    15000

    Sleep    3s

    ${result}=    Run Process
    ...    kubectl
    ...    exec
    ...    deployment/mosquitto
    ...    --
    ...    mosquitto_pub
    ...    -h
    ...    localhost
    ...    -p
    ...    1883
    ...    -t
    ...    home/e2e-test/air
    ...    -m
    ...    {"temperature":22.5}

    Should Be Equal As Integers    ${result.rc}    0

    ${result}=    Wait For Process    ${consumer}    timeout=20s
    ${kafka_message}=    Set Variable    ${result.stdout}

    Should Contain    ${kafka_message}    "roomId":"e2e-test"
    Should Contain    ${kafka_message}    "type":"AIR"
    Should Contain    ${kafka_message}    "temperature":22.5
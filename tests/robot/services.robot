*** Settings ***
Library    Collections
Library    RequestsLibrary
Library    Process
Library    String
Library    OperatingSystem

*** Variables ***
${BASE_URL}    http://127.0.0.1
${SENSOR_ID}   e2e-air-1
${OPENING_SENSOR_ID}    e2e-opening-1
${UNASSIGN_SENSOR_ID}   e2e-unassign-air-1
${CONFLICT_SENSOR_ID}   e2e-conflict-air-1

*** Test Cases ***
All services are up and running
    FOR    ${service}    ${path}    IN
    ...    environment-monitor    /live
    ...    occupancy-monitor    /live
    ...    home-api    /live
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
    ...    home-api    /ready
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
    ...    robot-e2e-mqtt-kafka
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
    ...    home/sensors/air
    ...    -m
    ...    {"sensorId":"${SENSOR_ID}","temperature":22.5,"humidity":89,"co2":1000}

    Should Be Equal As Integers    ${result.rc}    0

    ${result}=    Wait For Process    ${consumer}    timeout=20s
    ${kafka_message}=    Set Variable    ${result.stdout}

    Should Contain    ${kafka_message}    "schemaVersion":2
    Should Contain    ${kafka_message}    "sensorId":"${SENSOR_ID}"
    Should Contain    ${kafka_message}    "type":"AIR"
    Should Contain    ${kafka_message}    "temperature":22.5
    Should Contain    ${kafka_message}    "humidity":89
    Should Contain    ${kafka_message}    "co2":1000
    Should Not Contain    ${kafka_message}    "roomId"
    Should Not Contain    ${kafka_message}    "deviceId"

Sensor room assignment and history snapshots
    ${home_headers}=    Create Dictionary    Host=home-api.local    Content-Type=application/json
    Create Session    home-api    ${BASE_URL}    headers=${home_headers}

    ${room_a}=    POST On Session    home-api    /api/v1/rooms
    ...    data={"name":"E2E Room A","description":"first room"}
    Should Be Equal As Integers    ${room_a.status_code}    201
    ${room_a_id}=    Set Variable    ${room_a.json()}[roomId]

    ${room_b}=    POST On Session    home-api    /api/v1/rooms
    ...    data={"name":"E2E Room B"}
    Should Be Equal As Integers    ${room_b.status_code}    201
    ${room_b_id}=    Set Variable    ${room_b.json()}[roomId]

    Publish Air Event    ${SENSOR_ID}    21.0
    Wait Until Keyword Succeeds    30s    2s    Sensor Exists Unassigned    ${SENSOR_ID}

    ${assign}=    PUT On Session    home-api    /api/v1/rooms/${room_a_id}/sensors/${SENSOR_ID}
    Should Be Equal As Integers    ${assign.status_code}    200
    Should Be Equal    ${assign.json()}[roomId]    ${room_a_id}

    Publish Air Event    ${SENSOR_ID}    22.0
    Wait Until Keyword Succeeds    30s    2s    Room Has Reading With Temperature    ${room_a_id}    22.0

    ${rename}=    PATCH On Session    home-api    /api/v1/sensors/${SENSOR_ID}
    ...    data={"displayName":"E2E air sensor"}
    Should Be Equal As Integers    ${rename.status_code}    200
    Should Be Equal    ${rename.json()}[displayName]    E2E air sensor

    ${move}=    PUT On Session    home-api    /api/v1/rooms/${room_b_id}/sensors/${SENSOR_ID}
    Should Be Equal As Integers    ${move.status_code}    200
    Should Be Equal    ${move.json()}[roomId]    ${room_b_id}

    Publish Air Event    ${SENSOR_ID}    23.0
    Wait Until Keyword Succeeds    30s    2s    Room Has Reading With Temperature    ${room_b_id}    23.0
    Wait Until Keyword Succeeds    30s    2s    Room Has Reading With Temperature    ${room_a_id}    22.0

    ${room_a_history}=    GET On Session    home-api    /api/v1/rooms/${room_a_id}/environment-readings
    ${temps_a}=    Evaluate    [item['temperature'] for item in $room_a_history.json()['items']]
    List Should Contain Value    ${temps_a}    ${22.0}
    List Should Not Contain Value    ${temps_a}    ${23.0}

    ${room_b_history}=    GET On Session    home-api    /api/v1/rooms/${room_b_id}/environment-readings
    ${temps_b}=    Evaluate    [item['temperature'] for item in $room_b_history.json()['items']]
    List Should Contain Value    ${temps_b}    ${23.0}

Gateway status heartbeat still works
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
    ...    home/gateway/status
    ...    -m
    ...    {"status":"ONLINE"}
    Should Be Equal As Integers    ${result.rc}    0

    ${home_headers}=    Create Dictionary    Host=home-api.local
    Create Session    home-api-status    ${BASE_URL}    headers=${home_headers}
    Wait Until Keyword Succeeds    30s    2s    Gateway Is Online

Opening event reaches Kafka and room occupancy history
    ${consumer}=    Start Process
    ...    kubectl
    ...    exec
    ...    deployment/kafka
    ...    --
    ...    /opt/kafka/bin/kafka-console-consumer.sh
    ...    --bootstrap-server
    ...    kafka:9092
    ...    --topic
    ...    event-data
    ...    --group
    ...    robot-e2e-opening-kafka
    ...    --from-beginning
    ...    --timeout-ms
    ...    15000

    Sleep    3s

    ${home_headers}=    Create Dictionary    Host=home-api.local    Content-Type=application/json
    Create Session    home-api    ${BASE_URL}    headers=${home_headers}

    ${room}=    POST On Session    home-api    /api/v1/rooms
    ...    data={"name":"E2E Opening Room","description":"occupancy path"}
    Should Be Equal As Integers    ${room.status_code}    201
    ${room_id}=    Set Variable    ${room.json()}[roomId]

    Publish Opening Event    ${OPENING_SENSOR_ID}    OPEN

    ${result}=    Wait For Process    ${consumer}    timeout=20s
    ${kafka_message}=    Set Variable    ${result.stdout}
    Should Contain    ${kafka_message}    "schemaVersion":2
    Should Contain    ${kafka_message}    "sensorId":"${OPENING_SENSOR_ID}"
    Should Contain    ${kafka_message}    "type":"OPENING"
    Should Not Contain    ${kafka_message}    "roomId"
    Should Not Contain    ${kafka_message}    "deviceId"

    Wait Until Keyword Succeeds    30s    2s    Sensor Exists Unassigned    ${OPENING_SENSOR_ID}

    ${assign}=    PUT On Session    home-api    /api/v1/rooms/${room_id}/sensors/${OPENING_SENSOR_ID}
    Should Be Equal As Integers    ${assign.status_code}    200
    Should Be Equal    ${assign.json()}[roomId]    ${room_id}

    Publish Opening Event    ${OPENING_SENSOR_ID}    CLOSED
    Wait Until Keyword Succeeds    30s    2s    Room Has Occupancy Event    ${room_id}    ${OPENING_SENSOR_ID}    ${False}

Unassign stops new environment history on old room
    ${home_headers}=    Create Dictionary    Host=home-api.local    Content-Type=application/json
    Create Session    home-api    ${BASE_URL}    headers=${home_headers}

    ${room}=    POST On Session    home-api    /api/v1/rooms
    ...    data={"name":"E2E Unassign Room"}
    Should Be Equal As Integers    ${room.status_code}    201
    ${room_id}=    Set Variable    ${room.json()}[roomId]

    Publish Air Event    ${UNASSIGN_SENSOR_ID}    21.0
    Wait Until Keyword Succeeds    30s    2s    Sensor Exists Unassigned    ${UNASSIGN_SENSOR_ID}

    ${assign}=    PUT On Session    home-api    /api/v1/rooms/${room_id}/sensors/${UNASSIGN_SENSOR_ID}
    Should Be Equal As Integers    ${assign.status_code}    200
    Should Be Equal    ${assign.json()}[roomId]    ${room_id}

    Publish Air Event    ${UNASSIGN_SENSOR_ID}    24.5
    Wait Until Keyword Succeeds    30s    2s    Room Has Reading With Temperature    ${room_id}    24.5

    ${unassign}=    DELETE On Session    home-api    /api/v1/rooms/${room_id}/sensors/${UNASSIGN_SENSOR_ID}
    Should Be Equal As Integers    ${unassign.status_code}    200
    Should Be Equal    ${unassign.json()}[roomId]    ${None}

    ${room_after}=    GET On Session    home-api    /api/v1/rooms/${room_id}
    Should Be Equal As Integers    ${room_after.status_code}    200
    List Should Not Contain Value    ${room_after.json()}[sensorIds]    ${UNASSIGN_SENSOR_ID}

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
    ...    robot-e2e-unassign-kafka
    ...    --from-beginning
    ...    --timeout-ms
    ...    15000

    Sleep    3s
    Publish Air Event    ${UNASSIGN_SENSOR_ID}    25.5
    ${result}=    Wait For Process    ${consumer}    timeout=20s
    Should Contain    ${result.stdout}    "sensorId":"${UNASSIGN_SENSOR_ID}"
    Should Contain    ${result.stdout}    "temperature":25.5

    ${history}=    GET On Session    home-api    /api/v1/rooms/${room_id}/environment-readings
    Should Be Equal As Integers    ${history.status_code}    200
    ${temps}=    Evaluate    [item['temperature'] for item in $history.json()['items']]
    List Should Contain Value    ${temps}    ${24.5}
    List Should Not Contain Value    ${temps}    ${25.5}

    Wait Until Keyword Succeeds    30s    2s    Sensor Exists Unassigned    ${UNASSIGN_SENSOR_ID}

Room PATCH updates name and description
    ${home_headers}=    Create Dictionary    Host=home-api.local    Content-Type=application/json
    Create Session    home-api    ${BASE_URL}    headers=${home_headers}

    ${room}=    POST On Session    home-api    /api/v1/rooms
    ...    data={"name":"E2E Patch Room","description":"before patch"}
    Should Be Equal As Integers    ${room.status_code}    201
    ${room_id}=    Set Variable    ${room.json()}[roomId]

    ${patched}=    PATCH On Session    home-api    /api/v1/rooms/${room_id}
    ...    data={"name":"E2E Patch Room Renamed","description":"after patch"}
    Should Be Equal As Integers    ${patched.status_code}    200
    Should Be Equal    ${patched.json()}[name]    E2E Patch Room Renamed
    Should Be Equal    ${patched.json()}[description]    after patch

    ${got}=    GET On Session    home-api    /api/v1/rooms/${room_id}
    Should Be Equal As Integers    ${got.status_code}    200
    Should Be Equal    ${got.json()}[name]    E2E Patch Room Renamed
    Should Be Equal    ${got.json()}[description]    after patch

    ${list}=    GET On Session    home-api    /api/v1/rooms
    Should Be Equal As Integers    ${list.status_code}    200
    ${names}=    Evaluate    [item['name'] for item in $list.json()['rooms']]
    List Should Contain Value    ${names}    E2E Patch Room Renamed

Unassign from wrong room returns 409
    ${home_headers}=    Create Dictionary    Host=home-api.local    Content-Type=application/json
    Create Session    home-api    ${BASE_URL}    headers=${home_headers}

    ${room_a}=    POST On Session    home-api    /api/v1/rooms
    ...    data={"name":"E2E Conflict Room A"}
    Should Be Equal As Integers    ${room_a.status_code}    201
    ${room_a_id}=    Set Variable    ${room_a.json()}[roomId]

    ${room_b}=    POST On Session    home-api    /api/v1/rooms
    ...    data={"name":"E2E Conflict Room B"}
    Should Be Equal As Integers    ${room_b.status_code}    201
    ${room_b_id}=    Set Variable    ${room_b.json()}[roomId]

    Publish Air Event    ${CONFLICT_SENSOR_ID}    20.0
    Wait Until Keyword Succeeds    30s    2s    Sensor Exists Unassigned    ${CONFLICT_SENSOR_ID}

    ${assign}=    PUT On Session    home-api    /api/v1/rooms/${room_a_id}/sensors/${CONFLICT_SENSOR_ID}
    Should Be Equal As Integers    ${assign.status_code}    200
    Should Be Equal    ${assign.json()}[roomId]    ${room_a_id}

    ${conflict}=    DELETE On Session    home-api    /api/v1/rooms/${room_b_id}/sensors/${CONFLICT_SENSOR_ID}
    ...    expected_status=409
    Should Be Equal As Integers    ${conflict.status_code}    409

    ${sensor}=    GET On Session    home-api    /api/v1/sensors/${CONFLICT_SENSOR_ID}
    Should Be Equal As Integers    ${sensor.status_code}    200
    Should Be Equal    ${sensor.json()}[roomId]    ${room_a_id}

    ${unassign}=    DELETE On Session    home-api    /api/v1/rooms/${room_a_id}/sensors/${CONFLICT_SENSOR_ID}
    Should Be Equal As Integers    ${unassign.status_code}    200
    Should Be Equal    ${unassign.json()}[roomId]    ${None}

*** Keywords ***
Publish Air Event
    [Arguments]    ${sensor_id}    ${temperature}
    ${payload}=    Set Variable    {"sensorId":"${sensor_id}","temperature":${temperature},"humidity":45,"co2":700}
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
    ...    home/sensors/air
    ...    -m
    ...    ${payload}
    Should Be Equal As Integers    ${result.rc}    0

Publish Opening Event
    [Arguments]    ${sensor_id}    ${state}
    ${payload}=    Set Variable    {"sensorId":"${sensor_id}","state":"${state}"}
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
    ...    home/sensors/opening
    ...    -m
    ...    ${payload}
    Should Be Equal As Integers    ${result.rc}    0

Sensor Exists Unassigned
    [Arguments]    ${sensor_id}
    ${response}=    GET On Session    home-api    /api/v1/sensors/${sensor_id}
    Should Be Equal As Integers    ${response.status_code}    200
    Should Be Equal    ${response.json()}[sensorId]    ${sensor_id}
    Should Be Equal    ${response.json()}[roomId]    ${None}

Room Has Reading With Temperature
    [Arguments]    ${room_id}    ${temperature}
    ${response}=    GET On Session    home-api    /api/v1/rooms/${room_id}/environment-readings
    Should Be Equal As Integers    ${response.status_code}    200
    ${temps}=    Evaluate    [item['temperature'] for item in $response.json()['items']]
    List Should Contain Value    ${temps}    ${temperature}

Room Has Occupancy Event
    [Arguments]    ${room_id}    ${sensor_id}    ${open}
    ${response}=    GET On Session    home-api    /api/v1/rooms/${room_id}/occupancy-events
    Should Be Equal As Integers    ${response.status_code}    200
    ${matches}=    Evaluate    [item for item in $response.json()['items'] if item.get('sensorId') == $sensor_id and item.get('eventType') == 'OPENING' and item.get('open') == $open]
    Should Not Be Empty    ${matches}

Gateway Is Online
    ${response}=    GET On Session    home-api-status    /api/v1/sensor-gateway/status
    Should Be Equal As Integers    ${response.status_code}    200
    Should Be True    ${response.json()}[online]

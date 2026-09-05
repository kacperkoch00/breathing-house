*** Settings ***
Library    Collections
Library    RequestsLibrary

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
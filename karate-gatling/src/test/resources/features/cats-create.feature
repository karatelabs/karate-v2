Feature: Create Cat with Gatling Variables

  Background:
    # Use port from karate-config.js or default
    * def port = karate.get('mockPort', 53576)
    * url 'http://localhost:' + port

  Scenario: Create a cat using feeder data
    # Debug: print what's available
    * print 'arg:', __arg
    * print 'gatling:', __gatling
    # Get name from Gatling session (set via karateSet)
    * def catName = __gatling.name
    * def catAge = __gatling.age
    * print 'catName:', catName, 'catAge:', catAge

    # Verify the variables are defined before using them
    * match catName == 'TestKitty'
    * match catAge == 3

    Given path 'cats'
    And request { name: '#(catName)', age: '#(catAge)' }
    When method post
    Then status 201
    * print 'response:', response
    And match response contains { id: '#string', name: '#(catName)' }

    # Store cat ID for next feature in chain
    * def catId = response.id

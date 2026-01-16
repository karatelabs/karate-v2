@lock=tabs
Feature: Tab Switch Tests

  Scenario: Tab switching operations
    * configure driver = driverConfig
    * driver serverUrl + '/tab-main'
    * waitFor('#open-tab')

    # Verify initial state
    * def initialCount = getPages().length
    * assert initialCount >= 1

    # Open new tab and verify
    * click('#open-tab')
    * delay(500)
    * assert getPages().length == initialCount + 1

    # Switch to new tab by title and verify content
    * switchPage('Tab New Page')
    * match text('#title') == 'Tab New Page'
    * match script('window.pageType') == 'new'

    # Switch back by URL and verify content
    * switchPage('/tab-main')
    * match text('#title') == 'Tab Main Page'
    * match script('window.pageType') == 'main'

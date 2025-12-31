Feature: Keyboard Tests
  Keyboard operations and key presses

  Background:
    * configure driver = driverConfig
    * driver serverUrl + '/input'

  Scenario: Type text
    * focus('#username')
    * keys().type('hello')
    * def val = value('#username')
    * match val == 'hello'

  Scenario: Type with numbers
    * focus('#username')
    * keys().type('user123')
    * def val = value('#username')
    * match val == 'user123'

  Scenario: Type with special characters
    * focus('#email')
    * keys().type('test@example.com')
    * def val = value('#email')
    * match val == 'test@example.com'

  Scenario: Tab key navigation
    * focus('#username')
    * keys().type('user1')
    * keys().press(Key.TAB)
    * keys().type('test@test.com')
    * def emailValue = value('#email')
    * match emailValue == 'test@test.com'

  Scenario: Enter key
    * focus('#username')
    * keys().press(Key.ENTER)

  Scenario: Backspace key
    * focus('#username')
    * keys().type('hello world')
    * keys().press(Key.BACKSPACE)
    * keys().press(Key.BACKSPACE)
    * keys().press(Key.BACKSPACE)
    * keys().press(Key.BACKSPACE)
    * keys().press(Key.BACKSPACE)
    * def val = value('#username')
    * match val == 'hello '

  Scenario: Arrow keys
    * focus('#username')
    * keys().type('abc')
    * keys().press(Key.LEFT)
    * keys().press(Key.LEFT)
    * keys().type('X')
    * def val = value('#username')
    * match val == 'aXbc'

  Scenario: Ctrl+A select all
    * input('#username', 'select me')
    * focus('#username')
    * keys().ctrl('a')
    * keys().type('replaced')
    * def val = value('#username')
    * match val == 'replaced'

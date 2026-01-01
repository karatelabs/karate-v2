#!/usr/bin/env python3
"""Test the karate agent with a simple website."""

import subprocess
import json
import sys

def send(proc, js_code):
    """Send a command and get response."""
    msg = json.dumps({"command": "eval", "payload": js_code})
    print(f">>> {js_code}")
    proc.stdin.write(msg + "\n")
    proc.stdin.flush()
    response = proc.stdout.readline()
    result = json.loads(response)
    print(f"<<< {json.dumps(result, indent=2)}")
    return result

def main():
    # Start agent with sidebar for visibility
    proc = subprocess.Popen(
        ["java", "-jar", "karate-core/target/karate.jar", "agent", "--sidebar"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        bufsize=1
    )

    # Wait for ready signal
    ready = proc.stdout.readline()
    print(f"Ready: {ready.strip()}")

    try:
        # Test 1: Navigate to example.com
        send(proc, "agent.go('https://example.com')")

        # Test 2: Look at the page
        send(proc, "agent.look()")

        # Test 3: Navigate to a form page
        send(proc, "agent.go('https://httpbin.org/forms/post')")

        # Test 4: Look at the form
        result = send(proc, "agent.look()")

        # Test 5: Try to fill in a field if we found one
        if result.get("command") == "result":
            payload = result.get("payload", {})
            actions = payload.get("actions", {})
            if actions:
                first_ref = list(actions.keys())[0]
                first_actions = actions[first_ref]
                if "input" in first_actions:
                    send(proc, f"agent.act('{first_ref}', 'input', 'test value')")
                elif "click" in first_actions:
                    send(proc, f"agent.act('{first_ref}', 'click')")

        # Final look
        send(proc, "agent.look()")

        print("\n✓ Test completed successfully")

    except Exception as e:
        print(f"\n✗ Error: {e}")
        import traceback
        traceback.print_exc()
    finally:
        proc.stdin.close()
        proc.wait()

if __name__ == "__main__":
    main()

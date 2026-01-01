#!/usr/bin/env python3
"""Test the handoff feature - agent asks user to intervene."""

import subprocess
import json

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
    print("Starting agent with sidebar...")
    proc = subprocess.Popen(
        ["java", "-jar", "karate-core/target/karate.jar", "agent", "--sidebar"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        bufsize=1
    )

    # Wait for ready
    ready = proc.stdout.readline()
    print(f"Ready: {ready.strip()}\n")

    try:
        # Navigate to a page
        send(proc, "agent.go('https://example.com')")
        send(proc, "agent.look()")

        # Request handoff - this will block until user clicks Resume
        print("\n" + "="*50)
        print("HANDOFF TEST: Click 'Resume Agent' in the browser sidebar")
        print("="*50 + "\n")

        result = send(proc, "agent.handoff('Please click the Resume button to continue')")

        print("\n" + "="*50)
        print(f"User resumed after {result.get('payload', {}).get('elapsed', '?')}ms")
        print("="*50 + "\n")

        # Continue working after handoff
        send(proc, "agent.look()")

        print("\n✓ Handoff test completed successfully")

    except Exception as e:
        print(f"\n✗ Error: {e}")
        import traceback
        traceback.print_exc()
    finally:
        proc.stdin.close()
        proc.wait()

if __name__ == "__main__":
    main()

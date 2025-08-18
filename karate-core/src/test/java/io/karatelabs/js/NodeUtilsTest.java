package io.karatelabs.js;

import io.karatelabs.common.Resource;
import org.junit.jupiter.api.Test;

class NodeUtilsTest {

    @Test
    void testConversion() {
        Node node = new Node(NodeType.EXPR);
        Node c1 = new Node(NodeType.LIT_EXPR);
        node.children.add(c1);
        String text = "1";
        Token chunk = new Token(Resource.text(text), TokenType.NUMBER, 0,0, 0, text);
        Node c2 = new Node(chunk);
        c1.children.add(c2);
        NodeUtils.assertEquals(text, node, "1");
    }

}

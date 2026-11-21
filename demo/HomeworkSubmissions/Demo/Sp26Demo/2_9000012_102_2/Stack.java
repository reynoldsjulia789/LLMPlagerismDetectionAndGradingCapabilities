/* Demo submission (synthetic toy data, not student work): linked stack. */
public class Stack {
    private static class Node {
        int value;
        Node below;
        Node(int value, Node below) {
            this.value = value;
            this.below = below;
        }
    }

    private Node head;
    private int count;

    public void push(int value) {
        head = new Node(value, head);
        count++;
    }

    public int pop() {
        int v = peek();
        head = head.below;
        count--;
        return v;
    }

    public int peek() {
        if (head == null) {
            throw new IllegalStateException("stack is empty");
        }
        return head.value;
    }

    public boolean isEmpty() {
        return head == null;
    }

    public int size() {
        return count;
    }

    public int sum() {
        int total = 0;
        Stack copy = new Stack();
        while (!isEmpty()) {
            int v = pop();
            total += v;
            copy.push(v);
        }
        while (!copy.isEmpty()) {
            push(copy.pop());
        }
        return total;
    }
}

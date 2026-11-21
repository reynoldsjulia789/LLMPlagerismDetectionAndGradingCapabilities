/* Demo submission (synthetic toy data, not student work): stack using ArrayDeque. */
import java.util.ArrayDeque;
import java.util.Deque;

public class Stack {
    private final Deque<Integer> stack = new ArrayDeque<>();

    public void push(int value) {
        stack.push(value);
    }

    public int pop() {
        Integer v = stack.poll();
        if (v == null) {
            throw new IllegalStateException("underflow");
        }
        return v;
    }

    public int peek() {
        Integer v = stack.peek();
        if (v == null) {
            throw new IllegalStateException("underflow");
        }
        return v;
    }

    public boolean isEmpty() {
        return stack.isEmpty();
    }

    public int size() {
        return stack.size();
    }

    public void clear() {
        while (!isEmpty()) {
            pop();
        }
    }
}

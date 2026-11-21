/* Demo submission (synthetic toy data, not student work): stack on top of an ArrayList. */
import java.util.ArrayList;

public class Stack {
    private final ArrayList<Integer> items = new ArrayList<>();

    public void push(int value) {
        items.add(value);
    }

    public int pop() {
        checkNotEmpty();
        return items.remove(items.size() - 1);
    }

    public int peek() {
        checkNotEmpty();
        return items.get(items.size() - 1);
    }

    private void checkNotEmpty() {
        if (items.isEmpty()) {
            throw new RuntimeException("stack is empty");
        }
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int size() {
        return items.size();
    }

    public void clear() {
        while (!isEmpty()) {
            pop();
        }
    }
}

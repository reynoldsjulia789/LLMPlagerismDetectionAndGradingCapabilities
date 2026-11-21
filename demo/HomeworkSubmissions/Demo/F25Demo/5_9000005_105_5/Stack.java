/* Demo submission (synthetic toy data, not student work): stack on top of an ArrayList. */
import java.util.ArrayList;

public class Stack {
    private final ArrayList<Integer> values = new ArrayList<>();

    public void push(int value) {
        values.add(value);
    }

    public int pop() {
        checkNotEmpty();
        return values.remove(values.size() - 1);
    }

    public int peek() {
        checkNotEmpty();
        return values.get(values.size() - 1);
    }

    private void checkNotEmpty() {
        if (values.isEmpty()) {
            throw new RuntimeException("empty");
        }
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public int size() {
        return values.size();
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

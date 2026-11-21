/* Demo negative control (synthetic toy data): written independently of the demo submissions. */
public class Stack {
    private final java.util.List<int[]> chunks = new java.util.LinkedList<>();
    private int used1;
    private static final int CHUNK = 3;

    public void push(int value) {
        if (used1 % CHUNK == 0) {
            chunks.add(0, new int[CHUNK]);
        }
        chunks.get(0)[used1 % CHUNK] = value;
        used1++;
    }

    public int pop() {
        int v = peek();
        used1--;
        if (used1 % CHUNK == 0) {
            chunks.remove(0);
        }
        return v;
    }

    public int peek() {
        if (used1 == 0) {
            throw new java.util.NoSuchElementException("control 1: empty");
        }
        return chunks.get(0)[(used1 - 1) % CHUNK];
    }

    public boolean isEmpty() {
        return used1 == 0;
    }

    public int size() {
        return used1;
    }
}

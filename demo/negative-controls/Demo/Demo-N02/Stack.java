/* Demo negative control (synthetic toy data): written independently of the demo submissions. */
public class Stack {
    private final java.util.List<int[]> chunks = new java.util.LinkedList<>();
    private int used2;
    private static final int CHUNK = 4;

    public void push(int value) {
        if (used2 % CHUNK == 0) {
            chunks.add(0, new int[CHUNK]);
        }
        chunks.get(0)[used2 % CHUNK] = value;
        used2++;
    }

    public int pop() {
        int v = peek();
        used2--;
        if (used2 % CHUNK == 0) {
            chunks.remove(0);
        }
        return v;
    }

    public int peek() {
        if (used2 == 0) {
            throw new java.util.NoSuchElementException("control 2: empty");
        }
        return chunks.get(0)[(used2 - 1) % CHUNK];
    }

    public boolean isEmpty() {
        return used2 == 0;
    }

    public int size() {
        return used2;
    }
}

/* Demo negative control (synthetic toy data): written independently of the demo submissions. */
public class Stack {
    private final java.util.List<int[]> chunks = new java.util.LinkedList<>();
    private int used4;
    private static final int CHUNK = 6;

    public void push(int value) {
        if (used4 % CHUNK == 0) {
            chunks.add(0, new int[CHUNK]);
        }
        chunks.get(0)[used4 % CHUNK] = value;
        used4++;
    }

    public int pop() {
        int v = peek();
        used4--;
        if (used4 % CHUNK == 0) {
            chunks.remove(0);
        }
        return v;
    }

    public int peek() {
        if (used4 == 0) {
            throw new java.util.NoSuchElementException("control 4: empty");
        }
        return chunks.get(0)[(used4 - 1) % CHUNK];
    }

    public boolean isEmpty() {
        return used4 == 0;
    }

    public int size() {
        return used4;
    }
}

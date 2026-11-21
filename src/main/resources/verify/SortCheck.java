import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Random;

/**
 * Independent HW8 check, compiled together with a candidate solution: MergeSort() and InsertionSort()
 * must both equal Arrays.sort on identical seeded input (random, many duplicates, single element).
 * Uses the method names from the assignment's pseudocode via reflection.
 */
public class SortCheck {
    static Method byName(Class<?> c, String name, int params) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == params) {
                return m;
            }
        }
        throw new IllegalStateException("LinkedList has no public " + name + " with " + params + " parameter(s)");
    }

    static int[] run(String sort, int[] input) throws Exception {
        Class<?> c = Class.forName("LinkedList");
        Object list = c.getDeclaredConstructor().newInstance();
        Method addLast = byName(c, "addLast", 1);
        Method getFirst = byName(c, "getFirst", 0);
        Method removeFirst = byName(c, "removeFirst", 0);
        Method isEmpty = byName(c, "isEmpty", 0);
        for (int v : input) {
            addLast.invoke(list, Integer.valueOf(v));
        }
        byName(c, sort, 0).invoke(list);
        int[] out = new int[input.length];
        int k = 0;
        while (!(Boolean) isEmpty.invoke(list)) {
            if (k == input.length) {
                throw new IllegalStateException(sort + " added elements");
            }
            out[k++] = ((Number) getFirst.invoke(list)).intValue();
            removeFirst.invoke(list);
        }
        if (k != input.length) {
            throw new IllegalStateException(sort + " lost elements: " + k + " of " + input.length);
        }
        return out;
    }

    public static void main(String[] a) throws Exception {
        for (long seed : new long[]{1, 2, 3}) {
            Random r = new Random(seed);
            int n = seed == 3 ? 1 : 5000;
            int[] input = new int[n];
            for (int i = 0; i < n; i++) {
                input[i] = r.nextInt(seed == 2 ? 50 : 3000000);
            }
            int[] expected = input.clone();
            Arrays.sort(expected);
            for (String sort : new String[]{"MergeSort", "InsertionSort"}) {
                if (!Arrays.equals(expected, run(sort, input))) {
                    throw new IllegalStateException(sort + " produced a wrong order (seed " + seed + ")");
                }
            }
        }
        System.out.println("independent check OK");
    }
}

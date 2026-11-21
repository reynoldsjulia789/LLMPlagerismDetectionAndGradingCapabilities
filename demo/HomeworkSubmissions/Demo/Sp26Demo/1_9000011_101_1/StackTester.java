/* Demo tester (synthetic toy data). */
public class StackTester {
    public static void main(String[] args) {
        Stack s = new Stack();
        for (int i = 1; i <= 5; i++) {
            s.push(i * 10);
        }
        System.out.println(s.size() + " " + s.peek() + " " + s.pop() + " " + s.size());
    }
}

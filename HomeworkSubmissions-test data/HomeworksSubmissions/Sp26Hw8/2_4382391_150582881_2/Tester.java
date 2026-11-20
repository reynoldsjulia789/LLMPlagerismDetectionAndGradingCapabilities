import java.util.Random;

public class Tester {
    public static void main() {
        LinkedList<Integer> a = new LinkedList<>();
        LinkedList<Integer> a2 = new LinkedList<>();

        Random random = new Random();
        for (int i = 0; i < 20000; i++) {
            int rand = random.nextInt(3000000);
            a.add(rand);
            a2.add(rand);
        }

        double start = System.nanoTime();
        a.mergeSort();
        double end = System.nanoTime();
        System.out.println("Time cost in milliseconds for mergesort " + (end - start) / 1000000);
        System.out.println(a.isSorted());
        System.out.println("Size of list A is: " + a.size());

        double start2 = System.nanoTime();
        a2.insertionSort();
        double end2 = System.nanoTime();
        System.out.println("Time cost in milliseconds for insertionsort " + (end2 - start2) / 1000000);
        System.out.println(a2.isSorted());
        System.out.println("Size of list A2 is: " + a2.size());
    }
}

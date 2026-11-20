public class Tester {

    public static void main(String[] args) {
        LinkedList A = new LinkedList();
        LinkedList A2 = new LinkedList();
        int randomListSize = 20000;

        for (int i = 0; i < randomListSize; i++) {
            int value = (int)(Math.random() * 3000000);
            A.addLast(value);
            A2.addLast(value);
        }

        double then = System.currentTimeMillis();
        A.MergeSort();
        double now = System.currentTimeMillis();

        System.out.println("Time cost in milliseconds for mergesort " + (now - then));
        System.out.println(A.isSorted());
        System.out.println("Size of list A is: " + A.getSize());

        then = System.currentTimeMillis();
        A2.InsertionSort();
        now = System.currentTimeMillis();

        System.out.println("Time cost in milliseconds for insertionsort " + (now - then));
        System.out.println(A2.isSorted());
        System.out.println("Size of list A2 is: " + A2.getSize());
    }
}
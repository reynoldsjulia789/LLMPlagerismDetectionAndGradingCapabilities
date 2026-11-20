
public class Tester {

    public static void  main(String[] args) {


        LinkedList A = new LinkedList();
        LinkedList A2 = new LinkedList();

        int randomListSize = 20000;
        for (int i = 0; i < randomListSize; i++) {
            int randomInt = (int)(Math.random() * 3000000);
            A.addLast(randomInt);
            A2.addLast(randomInt);
        }
        // measure the time cost of merge sort
        double then = System.currentTimeMillis();
        A.MergeSort();
        double now = System.currentTimeMillis();
        System.out.println("Time cost in milliseconds for mergesort is " + (now - then));
        System.out.println(A.isSorted()); // verify merge sort works
        System.out.println("Size of list A is " + A.getSize());

        // measure the time cost of insertion sort
        then =  System.currentTimeMillis();
        A2.InsertionSort();
        now = System.currentTimeMillis();
        System.out.println("Time cost in milliseconds for insertionSort is " + (now - then));
        System.out.println(A2.isSorted()); // verify insertion sort works
        System.out.println("Size of list A is " + A2.getSize());
    }
}

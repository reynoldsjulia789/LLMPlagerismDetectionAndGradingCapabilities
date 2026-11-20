import java.util.Random;

public class tester {

    public static void main(String[] args) {
        Random r = new Random();
        CDoublyLinkedList A = new CDoublyLinkedList();
        CDoublyLinkedList A2 = new CDoublyLinkedList();
    for(int i = 0; i < 2000; i++){
        int rand = r.nextInt(3000000);
        A.addFirst(rand);
        A2.addFirst(rand);
    }   


A.mergeSort();
A2.insertionSort();

if(A.isSorted() == true){
        System.out.println("List A is sorted.");
    } else {
        System.out.println("List A is not sorted");
    }

    if(A2.isSorted() == true){
        System.out.println("List A2 is sorted");
    } else {
        System.out.println("List A2 is not sorted");
    }
    }
}
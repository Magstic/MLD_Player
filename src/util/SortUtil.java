package util;

import java.util.Vector;

public final class SortUtil {
    private SortUtil() {
    }

    public static interface Comparator {
        int compare(Object left, Object right);
    }

    public static void sort(Vector values, Comparator comparator) {
        if (values == null || comparator == null || values.size() < 2) {
            return;
        }
        quickSort(values, comparator, 0, values.size() - 1);
    }

    private static void quickSort(Vector values, Comparator comparator, int low, int high) {
        int left = low;
        int right = high;
        Object pivot = values.elementAt((low + high) / 2);

        while (left <= right) {
            while (comparator.compare(values.elementAt(left), pivot) < 0) {
                left++;
            }
            while (comparator.compare(values.elementAt(right), pivot) > 0) {
                right--;
            }
            if (left <= right) {
                swap(values, left, right);
                left++;
                right--;
            }
        }

        if (low < right) {
            quickSort(values, comparator, low, right);
        }
        if (left < high) {
            quickSort(values, comparator, left, high);
        }
    }

    private static void swap(Vector values, int left, int right) {
        Object tmp = values.elementAt(left);
        values.setElementAt(values.elementAt(right), left);
        values.setElementAt(tmp, right);
    }
}

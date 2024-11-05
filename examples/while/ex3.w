vars:
  int z;
  int sum;
  int max;
pre: (true)
code:
    sum := 0;
    z := 1000;
    max := z;
    while(z > 0) {
        sum := z + sum;
        z := z - 1;
    };
    print "", max, sum;
post: (sum = ((max*(max + 1))/2))
vars:
  int z;
  int sum;
  int max;
pre: (true)
code:
    sum := 1;
    extern z 1..3;
    max := z;
    assert (z >= 0 and z <=3);
    while(z > 0) {
        sum := z + sum;
        z := z - 1;
    };
    print "", sum;
post: (sum = ((max*(max + 1))/2))
vars:
  int* x;
  int[3] y;
  int z;
pre: (z>= 0 and z <=2)
code:
  y[z] := 1;
  assert (y[z] = 2);
post: (true)
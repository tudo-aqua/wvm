vars:
  int* x;
  int[3] y;
  int z;
pre: (z >= 0 and (z < 3 and y[z] = 1))
code:
  y[1] := 2;
  assert (y[z] = 2);
post: (true)
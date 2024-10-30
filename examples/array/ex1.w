vars:
  int* x;
  int[3] y;
  int z;
pre: (true)
code:
  y[0] := 1;
  y[1] := 2;
  y[2] := 7;
  x := y;
  print "x[1] = y[1] = ", x[1];
post: (true)

vars:
  int z;
pre: (true)
code:
    extern z -10..10;
    assert (z >= -10 and z <=10);
    if(z < 0) {
        assert (z<0);
    } else {
        assert (z>=0);
    };
post: (true)
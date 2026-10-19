import 'package:flutter/material.dart';
import '../models/destination.dart';

IconData iconFor(DestinationIcon icon) => switch (icon) {
  DestinationIcon.navigation => Icons.navigation,
  DestinationIcon.home => Icons.home_outlined,
  DestinationIcon.work => Icons.work_outline,
  DestinationIcon.office => Icons.apartment,
  DestinationIcon.shopping => Icons.shopping_cart_outlined,
};

String iconLabel(DestinationIcon icon) => switch (icon) {
  DestinationIcon.navigation => 'Navigation',
  DestinationIcon.home => 'Home',
  DestinationIcon.work => 'Work',
  DestinationIcon.office => 'Office',
  DestinationIcon.shopping => 'Shopping',
};

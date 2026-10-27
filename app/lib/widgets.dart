import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import 'api.dart';

/// Pide un texto (nombre de búsqueda, palabra clave...). Devuelve null si se cancela.
Future<String?> askText(BuildContext context, {required String title, String initial = '', String hint = ''}) {
  final controller = TextEditingController(text: initial);
  return showDialog<String>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(title),
      content: TextField(
        controller: controller,
        autofocus: true,
        decoration: InputDecoration(hintText: hint),
        onSubmitted: (value) => Navigator.pop(context, value.trim()),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancelar')),
        FilledButton(onPressed: () => Navigator.pop(context, controller.text.trim()), child: const Text('Aceptar')),
      ],
    ),
  ).then((value) => value == null || value.isEmpty ? null : value);
}

void showMessage(BuildContext context, String text) {
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
}

/// Tarjeta de una oferta puntuada. onOpen permite abrir la oferta dentro de la app.
class MatchCard extends StatelessWidget {
  final Match match;
  final void Function(String url)? onOpen;

  const MatchCard({super.key, required this.match, this.onOpen});

  @override
  Widget build(BuildContext context) {
    final o = match.offer;
    final subtitle = [
      ?o.company,
      ?o.city,
      if (o.description.isNotEmpty) o.description,
      ?o.salary,
    ].join(' · ');

    return Card(
      clipBehavior: Clip.antiAlias,
      child: ExpansionTile(
        leading: ScoreBadge(score: match.score),
        title: Text(o.title),
        subtitle: Text(subtitle),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        expandedCrossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (match.reason.isNotEmpty) Text(match.reason),
          const SizedBox(height: 8),
          ...match.matches.map((s) => _Line(icon: Icons.check_circle, color: Colors.green, text: s)),
          ...match.missing.map((s) => _Line(icon: Icons.cancel, color: Colors.redAccent, text: s)),
          if (o.url != null)
            Align(
              alignment: Alignment.centerRight,
              child: TextButton.icon(
                onPressed: () => onOpen != null
                    ? onOpen!(o.url!)
                    : launchUrl(Uri.parse(o.url!), mode: LaunchMode.externalApplication),
                icon: const Icon(Icons.open_in_new, size: 18),
                label: const Text('Ver oferta'),
              ),
            ),
        ],
      ),
    );
  }
}

class ScoreBadge extends StatelessWidget {
  final int score;

  const ScoreBadge({super.key, required this.score});

  @override
  Widget build(BuildContext context) {
    final color = score >= 80
        ? Colors.green
        : score >= 50
            ? Colors.orange
            : Colors.grey;
    return CircleAvatar(
      backgroundColor: color.withValues(alpha: 0.15),
      child: Text('$score', style: TextStyle(color: color, fontWeight: FontWeight.bold)),
    );
  }
}

class _Line extends StatelessWidget {
  final IconData icon;
  final Color color;
  final String text;

  const _Line({required this.icon, required this.color, required this.text});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 18, color: color),
          const SizedBox(width: 8),
          Expanded(child: Text(text)),
        ],
      ),
    );
  }
}
